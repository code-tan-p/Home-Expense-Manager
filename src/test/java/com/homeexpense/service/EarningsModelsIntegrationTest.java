package com.homeexpense.service;

import static com.homeexpense.service.EarningsFixture.counted;
import static com.homeexpense.service.EarningsFixture.d;
import static com.homeexpense.service.EarningsFixture.entryOn;
import static com.homeexpense.service.EarningsFixture.lines;
import static com.homeexpense.service.EarningsFixture.linesOn;
import static com.homeexpense.service.EarningsFixture.pending;
import static com.homeexpense.service.EarningsFixture.timed;
import static com.homeexpense.service.EarningsFixture.typeOf;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.EarningsResult;
import com.homeexpense.dto.NewPayRate;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.DeductionDecision;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.Shift;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkOutcome;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.WorkSchedule;
import com.homeexpense.model.Worker;
import com.homeexpense.model.earnings.EarningBasis;
import com.homeexpense.model.earnings.EarningLine;
import com.homeexpense.model.earnings.EarningLineType;
import com.homeexpense.model.earnings.ReviewDecisionKind;
import com.homeexpense.model.earnings.TreatmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Per-task, hourly, per-unit, per-day and per-shift earnings, including holidays and absences. */
@Transactional
class EarningsModelsIntegrationTest extends SqliteIntegrationTest {

    @Autowired
    private WorkerService workerService;
    @Autowired
    private CatalogService catalogService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private PayService payService;
    @Autowired
    private DailyWorkService dailyWorkService;
    @Autowired
    private EarningsService earningsService;

    private EarningsFixture fx;
    private Task sweeping;
    private Task mopping;
    private Shift morning;
    private Shift evening;

    @BeforeEach
    void setUp() {
        fx = new EarningsFixture(workerService, catalogService, scheduleService, payService, dailyWorkService, earningsService);
        sweeping = fx.task("Sweeping");
        mopping = fx.task("Mopping");
        morning = fx.fixtures.shift("Morning", "07:00", "09:00");
        evening = fx.fixtures.shift("Evening", "17:00", "19:00");
    }

    @Test
    void perTaskPaysEachDoneTaskAtItsRate() {
        Worker ravi = fx.worker("Ravi", d("2026-01-01"), PaymentModel.PER_TASK);
        WorkSchedule s = scheduleService.addScheduleVersion(ravi.getId(), d("2026-01-01"), List.of(
                NewScheduleEntry.task(MONDAY, sweeping.getId()), timed(MONDAY, mopping, "10:00", "11:00"),
                NewScheduleEntry.shift(TUESDAY, morning.getId())), null);
        fx.rate(ravi, RateType.PER_TASK, "50", d("2026-01-01"));
        fx.taskRate(ravi, RateType.PER_TASK, sweeping, "60", d("2026-01-01"));
        WorkRecord sweep = fx.scheduled(ravi, "2026-01-05", entryOn(s, MONDAY, sweeping), WorkDetails.completed());
        WorkRecord mop = fx.scheduled(ravi, "2026-01-05", entryOn(s, MONDAY, mopping), WorkDetails.completed());
        WorkRecord partial = fx.scheduled(ravi, "2026-01-12", entryOn(s, MONDAY, mopping), WorkDetails.partialMinutes(30));
        WorkRecord holiday = fx.scheduled(ravi, "2026-01-12", entryOn(s, MONDAY, sweeping), WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY));
        WorkRecord shiftHoliday = dailyWorkService.recordScheduled(ravi.getId(), d("2026-01-13"),
                s.getEntries().stream().filter(e -> e.getShift() != null).findFirst().orElseThrow().getId(),
                WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY)).record();
        WorkRecord leave = fx.scheduled(ravi, "2026-01-19", entryOn(s, MONDAY, sweeping), WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE));
        WorkRecord absent = fx.scheduled(ravi, "2026-01-19", entryOn(s, MONDAY, mopping), WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));
        WorkRecord approvedAbsence = fx.scheduled(ravi, "2026-01-26", entryOn(s, MONDAY, mopping), WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));
        dailyWorkService.decideDeduction(approvedAbsence.getId(), DeductionDecision.APPROVED, null);

        EarningsResult result = fx.january(ravi);

        assertThat(EarningsFixture.treatmentOf(result, sweep).getLine().getAmountPaise()).as("task-specific rate").isEqualTo(6000L);
        assertThat(EarningsFixture.treatmentOf(result, mop).getLine().getAmountPaise()).as("standard rate").isEqualTo(5000L);
        EarningLine holidayLine = EarningsFixture.treatmentOf(result, holiday).getLine();
        assertThat(holidayLine.getBasis()).isEqualTo(EarningBasis.HOLIDAY);
        assertThat(holidayLine.getAmountPaise()).isEqualTo(6000L);
        assertThat(typeOf(result, shiftHoliday)).as("holiday entry without a task").isEqualTo(TreatmentType.NO_APPLICABLE_RATE);
        assertThat(result.warnings()).anyMatch(w -> w.contains("has no task"));
        assertThat(typeOf(result, leave)).isEqualTo(TreatmentType.UNPAID_LEAVE);
        assertThat(typeOf(result, absent)).isEqualTo(TreatmentType.ABSENCE_UNPAID);
        assertThat(typeOf(result, approvedAbsence)).isEqualTo(TreatmentType.ABSENCE_UNPAID);

        EarningLine partialLine = EarningsFixture.treatmentOf(result, partial).getLine();
        assertThat(partialLine.getAmountPaise()).as("excluded until reviewed").isNull();
        assertThat(partialLine.getSuggestedAmountPaise()).as("5000 x 30/60").isEqualTo(2500L);
        assertThat(result.totalPaise()).isEqualTo(17000L);

        EarningsResult accepted = earningsService.reviewLine(partialLine.getId(), ReviewDecisionKind.ACCEPT_SUGGESTED, null, null);
        assertThat(accepted.totalPaise()).isEqualTo(19500L);
        assertThat(pending(accepted)).isEmpty();
    }

    @Test
    void hourlyPaysActualTimeAndHolidaysAtExpectedTime() {
        Task housework = fx.task("Housework");
        Task errands = fx.task("Errands");
        Worker hari = fx.worker("Hari", d("2026-01-01"), PaymentModel.HOURLY);
        WorkSchedule s = scheduleService.addScheduleVersion(hari.getId(), d("2026-01-01"), List.of(
                timed(MONDAY, housework, "07:00", "09:00"), NewScheduleEntry.task(TUESDAY, errands.getId())), null);
        fx.rate(hari, RateType.HOURLY, "83.33", d("2026-01-01"));
        WorkRecord done = fx.scheduled(hari, "2026-01-05", entryOn(s, MONDAY, housework), WorkDetails.completedMinutes(45));
        WorkRecord holiday = fx.scheduled(hari, "2026-01-12", entryOn(s, MONDAY, housework), WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY));
        WorkRecord untimedHoliday = fx.scheduled(hari, "2026-01-13", entryOn(s, TUESDAY, errands), WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY));
        WorkRecord partial = fx.scheduled(hari, "2026-01-19", entryOn(s, MONDAY, housework), WorkDetails.partialMinutes(30));

        EarningsResult result = fx.january(hari);

        assertThat(EarningsFixture.treatmentOf(result, done).getLine().getAmountPaise()).as("8333 x 45/60 = 6249.75").isEqualTo(6250L);
        assertThat(EarningsFixture.treatmentOf(result, holiday).getLine().getAmountPaise()).as("8333 x 120/60").isEqualTo(16666L);
        assertThat(EarningsFixture.treatmentOf(result, partial).getLine().getAmountPaise()).as("8333 x 30/60 = 4166.5").isEqualTo(4167L);
        EarningLine manual = EarningsFixture.treatmentOf(result, untimedHoliday).getLine();
        assertThat(manual.getAmountPaise()).isNull();
        assertThat(manual.getSuggestedAmountPaise()).as("no expected minutes, no guess").isNull();
        assertThat(result.totalPaise()).isEqualTo(27083L);

        EarningsResult overridden = earningsService.reviewLine(manual.getId(), ReviewDecisionKind.OVERRIDE, "100", "Usual errands pay");
        assertThat(overridden.totalPaise()).isEqualTo(37083L);
    }

    @Test
    void perUnitPaysActualQuantityAndNoHolidayPay() {
        Task milk = fx.task("Milk delivery");
        Worker milan = fx.worker("Milan", d("2026-01-01"), PaymentModel.PER_UNIT);
        WorkSchedule s = scheduleService.addScheduleVersion(milan.getId(), d("2026-01-01"), List.of(
                new NewScheduleEntry(TUESDAY, milk.getId(), null, null, null, null, 1500L)), null);
        payService.addRate(new NewPayRate(milan.getId(), RateType.PER_UNIT, null, null, "litre", "56.50", d("2026-01-01"), null));
        WorkRecord more = fx.scheduled(milan, "2026-01-06", entryOn(s, TUESDAY, milk), WorkDetails.completedQuantity(2000));
        WorkRecord less = fx.scheduled(milan, "2026-01-13", entryOn(s, TUESDAY, milk), WorkDetails.partialQuantity(1250));
        WorkRecord holiday = fx.scheduled(milan, "2026-01-20", entryOn(s, TUESDAY, milk), WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY));
        WorkRecord missed = fx.scheduled(milan, "2026-01-27", entryOn(s, TUESDAY, milk), WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));

        EarningsResult result = fx.january(milan);

        assertThat(EarningsFixture.treatmentOf(result, more).getLine().getAmountPaise()).isEqualTo(11300L);
        assertThat(EarningsFixture.treatmentOf(result, less).getLine().getAmountPaise()).as("5650 x 1.25 = 7062.5").isEqualTo(7063L);
        assertThat(EarningsFixture.treatmentOf(result, less).getLine().getUnitLabel()).isEqualTo("litre");
        assertThat(typeOf(result, holiday)).isEqualTo(TreatmentType.HOLIDAY_NOT_PAID);
        assertThat(typeOf(result, missed)).isEqualTo(TreatmentType.ABSENCE_UNPAID);
        assertThat(result.totalPaise()).isEqualTo(18363L);
        assertThat(pending(result)).isEmpty();
    }

    @Test
    void perDayPaysAtMostOneDayRatePerDate() {
        Task cooking = fx.task("Cooking");
        Worker devi = fx.worker("Devi", d("2026-01-01"), PaymentModel.PER_DAY);
        WorkSchedule s = scheduleService.addScheduleVersion(devi.getId(), d("2026-01-01"), List.of(
                timed(MONDAY, sweeping, "07:00", "08:00"), timed(MONDAY, cooking, "08:00", "10:00")), null);
        fx.rate(devi, RateType.PER_DAY, "300", d("2026-01-01"));
        // 5th: both done plus unscheduled mopping. 12th: one done, one partial.
        WorkRecord a = fx.scheduled(devi, "2026-01-05", entryOn(s, MONDAY, sweeping), WorkDetails.completed());
        WorkRecord b = fx.scheduled(devi, "2026-01-05", entryOn(s, MONDAY, cooking), WorkDetails.completed());
        WorkRecord extra = fx.unscheduled(devi, "2026-01-05", mopping, WorkDetails.completed());
        fx.scheduled(devi, "2026-01-12", entryOn(s, MONDAY, sweeping), WorkDetails.completed());
        WorkRecord partialCovered = fx.scheduled(devi, "2026-01-12", entryOn(s, MONDAY, cooking), WorkDetails.partialMinutes(60));
        // 19th: leave + partial, nothing done: suggestion 30000 x 60/180.
        fx.scheduled(devi, "2026-01-19", entryOn(s, MONDAY, sweeping), WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE));
        fx.scheduled(devi, "2026-01-19", entryOn(s, MONDAY, cooking), WorkDetails.partialMinutes(60));
        // 26th: holiday + leave: holiday share 60/180.
        WorkRecord holiday = fx.scheduled(devi, "2026-01-26", entryOn(s, MONDAY, sweeping), WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY));
        fx.scheduled(devi, "2026-01-26", entryOn(s, MONDAY, cooking), WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE));

        EarningsResult result = fx.january(devi);

        for (String date : List.of("2026-01-05", "2026-01-12", "2026-01-19", "2026-01-26")) {
            assertThat(linesOn(result, date)).filteredOn(l -> l.getLineType() == EarningLineType.PER_DAY).hasSize(1);
        }
        EarningLine fifth = linesOn(result, "2026-01-05").getFirst();
        assertThat(fifth.getAmountPaise()).isEqualTo(30000L);
        assertThat(EarningsFixture.treatmentOf(result, a).getLine().getId()).isEqualTo(fifth.getId());
        assertThat(EarningsFixture.treatmentOf(result, b).getLine().getId()).isEqualTo(fifth.getId());
        assertThat(typeOf(result, extra)).isEqualTo(TreatmentType.COVERED_BY_DAY_RATE);
        assertThat(typeOf(result, partialCovered)).isEqualTo(TreatmentType.COVERED_BY_DAY_RATE);
        assertThat(linesOn(result, "2026-01-19").getFirst().getSuggestedAmountPaise()).isEqualTo(10000L);
        assertThat(linesOn(result, "2026-01-19").getFirst().getAmountPaise()).isNull();
        EarningLine holidayLine = EarningsFixture.treatmentOf(result, holiday).getLine();
        assertThat(holidayLine.getBasis()).isEqualTo(EarningBasis.HOLIDAY);
        assertThat(holidayLine.getAmountPaise()).as("30000 x 60/180").isEqualTo(10000L);
        assertThat(result.totalPaise()).isEqualTo(70000L);
    }

    @Test
    void perDayHolidaysWholeDayOrMixedWithPartialWork() {
        Task cooking = fx.task("Cooking");
        Worker devi = fx.worker("Devi", d("2026-01-01"), PaymentModel.PER_DAY);
        WorkSchedule s = scheduleService.addScheduleVersion(devi.getId(), d("2026-01-01"), List.of(
                timed(MONDAY, sweeping, "07:00", "08:00"), timed(MONDAY, cooking, "08:00", "10:00")), null);
        fx.rate(devi, RateType.PER_DAY, "300", d("2026-01-01"));
        dailyWorkService.markWholeDay(devi.getId(), d("2026-02-02"), AbsenceReason.HOUSEHOLD_HOLIDAY, null);
        fx.scheduled(devi, "2026-02-09", entryOn(s, MONDAY, sweeping), WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY));
        fx.scheduled(devi, "2026-02-09", entryOn(s, MONDAY, cooking), WorkDetails.partialMinutes(60));

        EarningsResult result = earningsService.calculate(devi.getId(), YearMonth.of(2026, 2));

        assertThat(linesOn(result, "2026-02-02")).singleElement().satisfies(l -> {
            assertThat(l.getAmountPaise()).isEqualTo(30000L);
            assertThat(l.getBasis()).isEqualTo(EarningBasis.HOLIDAY);
        });
        assertThat(linesOn(result, "2026-02-09")).singleElement().satisfies(l -> {
            assertThat(l.getAmountPaise()).as("mixed holiday and partial work is not guessed").isNull();
            assertThat(l.getSuggestedAmountPaise()).isNull();
        });
    }

    @Test
    void perShiftPaysEachShiftAtItsRate() {
        Worker shanti = fx.worker("Shanti", d("2026-01-01"), PaymentModel.PER_SHIFT);
        WorkSchedule s = scheduleService.addScheduleVersion(shanti.getId(), d("2026-01-01"), List.of(
                NewScheduleEntry.shift(MONDAY, morning.getId()), NewScheduleEntry.shift(MONDAY, evening.getId()),
                NewScheduleEntry.task(TUESDAY, sweeping.getId())), null);
        fx.rate(shanti, RateType.PER_SHIFT, "150", d("2026-01-01"));
        payService.addRate(new NewPayRate(shanti.getId(), RateType.PER_SHIFT, null, evening.getId(), null, "200", d("2026-01-01"), null));
        Long morningEntry = s.getEntries().stream().filter(e -> e.getShift() != null && e.getShift().getId().equals(morning.getId())).findFirst().orElseThrow().getId();
        Long eveningEntry = s.getEntries().stream().filter(e -> e.getShift() != null && e.getShift().getId().equals(evening.getId())).findFirst().orElseThrow().getId();
        WorkRecord m = dailyWorkService.recordScheduled(shanti.getId(), d("2026-01-05"), morningEntry, WorkDetails.completed()).record();
        WorkRecord e = dailyWorkService.recordScheduled(shanti.getId(), d("2026-01-05"), eveningEntry, WorkDetails.completed()).record();
        WorkRecord holiday = dailyWorkService.recordScheduled(shanti.getId(), d("2026-01-12"), morningEntry,
                WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY)).record();
        WorkRecord partial = dailyWorkService.recordScheduled(shanti.getId(), d("2026-01-12"), eveningEntry,
                WorkDetails.partialMinutes(60)).record();
        WorkRecord taskHoliday = fx.scheduled(shanti, "2026-01-13", entryOn(s, TUESDAY, sweeping), WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY));

        EarningsResult result = fx.january(shanti);

        assertThat(EarningsFixture.treatmentOf(result, m).getLine().getAmountPaise()).isEqualTo(15000L);
        assertThat(EarningsFixture.treatmentOf(result, e).getLine().getAmountPaise()).as("evening-specific rate").isEqualTo(20000L);
        assertThat(EarningsFixture.treatmentOf(result, holiday).getLine().getAmountPaise()).isEqualTo(15000L);
        assertThat(EarningsFixture.treatmentOf(result, partial).getLine().getSuggestedAmountPaise()).as("20000 x 60/120").isEqualTo(10000L);
        assertThat(typeOf(result, taskHoliday)).as("holiday entry without a shift").isEqualTo(TreatmentType.NO_APPLICABLE_RATE);
        assertThat(result.totalPaise()).isEqualTo(50000L);
        assertThat(lines(result, EarningLineType.PER_SHIFT)).hasSize(4);
    }

    @Test
    void ratesAreTakenFromTheWorkDateNotToday() {
        Worker ravi = fx.worker("Ravi", d("2026-01-01"), PaymentModel.PER_TASK);
        WorkSchedule s = scheduleService.addScheduleVersion(ravi.getId(), d("2026-01-01"),
                List.of(NewScheduleEntry.task(MONDAY, sweeping.getId())), null);
        fx.rate(ravi, RateType.PER_TASK, "50", d("2026-01-01"));
        fx.rate(ravi, RateType.PER_TASK, "70", d("2026-01-19"));
        WorkRecord before = fx.scheduled(ravi, "2026-01-12", entryOn(s, MONDAY, sweeping), WorkDetails.completed());
        WorkRecord onChange = fx.scheduled(ravi, "2026-01-19", entryOn(s, MONDAY, sweeping), WorkDetails.completed());
        fx.rate(ravi, RateType.PER_TASK, "90", d("2026-06-01"));

        EarningsResult result = fx.january(ravi);

        assertThat(EarningsFixture.treatmentOf(result, before).getLine().getAmountPaise()).isEqualTo(5000L);
        assertThat(EarningsFixture.treatmentOf(result, onChange).getLine().getAmountPaise()).isEqualTo(7000L);
        assertThat(counted(result)).isEqualTo(result.totalPaise());
        assertThat(result.treatments()).extracting(t -> t.getWorkRecord().getOutcome()).containsOnly(WorkOutcome.COMPLETED);
    }
}
