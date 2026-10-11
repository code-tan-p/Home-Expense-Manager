package com.homeexpense.service;

import static com.homeexpense.service.EarningsFixture.MON_TO_SAT;
import static com.homeexpense.service.EarningsFixture.d;
import static com.homeexpense.service.EarningsFixture.entryOn;
import static com.homeexpense.service.EarningsFixture.lines;
import static com.homeexpense.service.EarningsFixture.linesOn;
import static com.homeexpense.service.EarningsFixture.timed;
import static com.homeexpense.service.EarningsFixture.treatmentOf;
import static com.homeexpense.service.EarningsFixture.typeOf;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.EarningsResult;
import com.homeexpense.dto.NewPayRate;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.WorkSchedule;
import com.homeexpense.model.Worker;
import com.homeexpense.model.earnings.EarningBasis;
import com.homeexpense.model.earnings.EarningLineType;
import com.homeexpense.model.earnings.TreatmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * C1-C3: unscheduled work is paid by exactly one rule. 2026-01-11 is a Sunday (a regular day
 * off for Monday-Saturday schedules) and 2026-01-05 a Monday.
 */
@Transactional
class EarningsExtraWorkIntegrationTest extends SqliteIntegrationTest {

    private static final String SUNDAY = "2026-01-11";
    private static final String MONDAY_5 = "2026-01-05";

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
    @Autowired
    private JdbcTemplate jdbc;

    private EarningsFixture fx;
    private Task housework;
    private Task carWash;
    private Task gardening;
    private Task ironing;

    @BeforeEach
    void setUp() {
        fx = new EarningsFixture(workerService, catalogService, scheduleService, payService, dailyWorkService, earningsService);
        housework = fx.task("Housework");
        carWash = fx.task("Car wash");
        gardening = fx.task("Gardening");
        ironing = fx.task("Ironing");
    }

    private Worker withMonToSat(String name, PaymentModel model) {
        Worker worker = fx.worker(name, d("2026-01-01"), model);
        fx.weekly(worker, d("2026-01-01"), MON_TO_SAT, day -> List.of(timed(day, housework, "07:00", "09:00")));
        return worker;
    }

    private void assertEveryRecordTreatedOnce(EarningsResult result, Worker worker) {
        int current = jdbc.queryForObject("SELECT COUNT(*) FROM work_record WHERE worker_id = ? AND voided_at IS NULL "
                + "AND work_date BETWEEN '2026-01-01' AND '2026-01-31'", Integer.class, worker.getId());
        assertThat(result.treatments()).hasSize(current);
        assertThat(result.treatments()).extracting(t -> t.getWorkRecord().getId()).doesNotHaveDuplicates();
    }

    // --- monthly salary ---

    @Test
    void salariedDayOffWorkIsPaidByOneExtraDayLine() {
        Worker sunita = withMonToSat("Sunita", PaymentModel.MONTHLY_SALARY);
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));
        fx.rate(sunita, RateType.EXTRA_DAY, "250", d("2026-01-01"));
        fx.rate(sunita, RateType.EXTRA_HOUR, "60", d("2026-01-01"));
        WorkRecord first = fx.unscheduled(sunita, SUNDAY, gardening, WorkDetails.completedMinutes(120));
        WorkRecord second = fx.unscheduled(sunita, SUNDAY, ironing, WorkDetails.completed());

        EarningsResult result = fx.january(sunita);

        assertThat(linesOn(result, SUNDAY)).singleElement().satisfies(l -> {
            assertThat(l.getLineType()).isEqualTo(EarningLineType.EXTRA_DAY);
            assertThat(l.getAmountPaise()).isEqualTo(25000L);
        });
        assertThat(typeOf(result, first)).isEqualTo(TreatmentType.COVERED_BY_EXTRA_DAY);
        assertThat(typeOf(result, second)).isEqualTo(TreatmentType.COVERED_BY_EXTRA_DAY);
        assertThat(lines(result, EarningLineType.EXTRA_HOUR)).as("extra-day takes priority, nothing paid twice").isEmpty();
        assertThat(lines(result, EarningLineType.MONTHLY_SALARY).getFirst().getScheduledDayUnits()).as("Sundays are not salary days").isEqualTo(27);
        assertEveryRecordTreatedOnce(result, sunita);
    }

    @Test
    void salariedWorkWithoutExtraDayUsesExtraTaskThenExtraHourThenWarns() {
        Worker sunita = withMonToSat("Sunita", PaymentModel.MONTHLY_SALARY);
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));
        fx.taskRate(sunita, RateType.EXTRA_TASK, carWash, "200", d("2026-01-01"));
        fx.rate(sunita, RateType.EXTRA_HOUR, "60", d("2026-01-01"));
        WorkRecord wash = fx.unscheduled(sunita, SUNDAY, carWash, WorkDetails.completedMinutes(90));
        WorkRecord garden = fx.unscheduled(sunita, SUNDAY, gardening, WorkDetails.completedMinutes(90));
        WorkRecord iron = fx.unscheduled(sunita, SUNDAY, ironing, WorkDetails.completed());
        WorkRecord weekdayWash = fx.unscheduled(sunita, MONDAY_5, carWash, WorkDetails.completed());

        EarningsResult result = fx.january(sunita);

        assertThat(treatmentOf(result, wash).getLine().getLineType()).as("extra-task beats extra-hour").isEqualTo(EarningLineType.EXTRA_TASK);
        assertThat(treatmentOf(result, wash).getLine().getAmountPaise()).isEqualTo(20000L);
        assertThat(treatmentOf(result, garden).getLine().getLineType()).isEqualTo(EarningLineType.EXTRA_HOUR);
        assertThat(treatmentOf(result, garden).getLine().getAmountPaise()).as("6000 x 90/60").isEqualTo(9000L);
        assertThat(typeOf(result, iron)).isEqualTo(TreatmentType.NO_APPLICABLE_RATE);
        assertThat(result.warnings()).anyMatch(w -> w.contains("Ironing") && w.contains("no configured rate"));
        assertThat(treatmentOf(result, weekdayWash).getLine().getLineType()).isEqualTo(EarningLineType.EXTRA_TASK);
        assertThat(treatmentOf(result, weekdayWash).getLine().getBasis()).isEqualTo(EarningBasis.EXTRA_WORK);
        assertEveryRecordTreatedOnce(result, sunita);
    }

    // --- hourly, per task and per shift ---

    @Test
    void hourlyWorkerUsesAgreedExtraTaskRateInsteadOfHourlyPay() {
        Worker hari = withMonToSat("Hari", PaymentModel.HOURLY);
        fx.rate(hari, RateType.HOURLY, "83.33", d("2026-01-01"));
        fx.taskRate(hari, RateType.EXTRA_TASK, carWash, "200", d("2026-01-01"));
        WorkRecord wash = fx.unscheduled(hari, MONDAY_5, carWash, WorkDetails.completedMinutes(30));
        WorkRecord garden = fx.unscheduled(hari, MONDAY_5, gardening, WorkDetails.completedMinutes(30));

        EarningsResult result = fx.january(hari);

        assertThat(treatmentOf(result, wash).getLine().getLineType()).isEqualTo(EarningLineType.EXTRA_TASK);
        assertThat(treatmentOf(result, wash).getLine().getAmountPaise()).isEqualTo(20000L);
        assertThat(treatmentOf(result, garden).getLine().getLineType()).as("primary rate prices it").isEqualTo(EarningLineType.HOURLY);
        assertThat(treatmentOf(result, garden).getLine().getAmountPaise()).isEqualTo(4167L);
        assertEveryRecordTreatedOnce(result, hari);
    }

    @Test
    void hourlyDayOffWorkWithExtraDayRateIsPaidOnlyByTheExtraDayLine() {
        Worker hari = withMonToSat("Hari", PaymentModel.HOURLY);
        fx.rate(hari, RateType.HOURLY, "83.33", d("2026-01-01"));
        fx.rate(hari, RateType.EXTRA_DAY, "400", d("2026-01-01"));
        WorkRecord garden = fx.unscheduled(hari, SUNDAY, gardening, WorkDetails.completedMinutes(120));

        EarningsResult result = fx.january(hari);

        assertThat(linesOn(result, SUNDAY)).singleElement().satisfies(l -> {
            assertThat(l.getLineType()).isEqualTo(EarningLineType.EXTRA_DAY);
            assertThat(l.getAmountPaise()).isEqualTo(40000L);
        });
        assertThat(typeOf(result, garden)).isEqualTo(TreatmentType.COVERED_BY_EXTRA_DAY);
        assertThat(lines(result, EarningLineType.HOURLY)).isEmpty();
    }

    @Test
    void perTaskDayOffWorkWithoutExtraDayUsesThePrimaryTaskRate() {
        Worker ravi = withMonToSat("Ravi", PaymentModel.PER_TASK);
        fx.rate(ravi, RateType.PER_TASK, "50", d("2026-01-01"));
        WorkRecord sundayTask = fx.unscheduled(ravi, SUNDAY, housework, WorkDetails.completed());

        EarningsResult result = fx.january(ravi);

        assertThat(treatmentOf(result, sundayTask).getLine().getLineType()).isEqualTo(EarningLineType.PER_TASK);
        assertThat(treatmentOf(result, sundayTask).getLine().getBasis()).isEqualTo(EarningBasis.EXTRA_WORK);
        assertThat(treatmentOf(result, sundayTask).getLine().getAmountPaise()).isEqualTo(5000L);
    }

    @Test
    void perShiftTaskWithoutAShiftFallsBackToExtraHourOrWarns() {
        Worker shanti = withMonToSat("Shanti", PaymentModel.PER_SHIFT);
        Worker noHourRate = withMonToSat("Shanti two", PaymentModel.PER_SHIFT);
        fx.rate(shanti, RateType.PER_SHIFT, "150", d("2026-01-01"));
        fx.rate(shanti, RateType.EXTRA_HOUR, "60", d("2026-01-01"));
        fx.rate(noHourRate, RateType.PER_SHIFT, "150", d("2026-01-01"));
        WorkRecord errand = fx.unscheduled(shanti, MONDAY_5, ironing, WorkDetails.completedMinutes(45));
        WorkRecord unpriced = fx.unscheduled(noHourRate, MONDAY_5, ironing, WorkDetails.completedMinutes(45));

        assertThat(treatmentOf(fx.january(shanti), errand).getLine().getAmountPaise()).as("6000 x 45/60").isEqualTo(4500L);
        assertThat(typeOf(fx.january(noHourRate), unpriced)).isEqualTo(TreatmentType.NO_APPLICABLE_RATE);
    }

    // --- per unit (C2) ---

    @Test
    void perUnitDayOffDeliveryIsPaidByQuantityNeverByExtraDay() {
        Task milk = fx.task("Milk delivery");
        Task groceries = fx.task("Carry groceries");
        Worker milan = fx.worker("Milan", d("2026-01-01"), PaymentModel.PER_UNIT);
        scheduleService.addScheduleVersion(milan.getId(), d("2026-01-01"), List.of(
                new NewScheduleEntry(TUESDAY, milk.getId(), null, null, null, null, 1500L)), null);
        payService.addRate(new NewPayRate(milan.getId(), RateType.PER_UNIT, null, null, "litre", "56.50", d("2026-01-01"), null));
        fx.rate(milan, RateType.EXTRA_DAY, "100", d("2026-01-01"));
        fx.taskRate(milan, RateType.EXTRA_TASK, groceries, "30", d("2026-01-01"));
        WorkRecord sundayMilk = fx.unscheduled(milan, SUNDAY, milk, WorkDetails.completedQuantity(1000));
        WorkRecord sundayGroceries = fx.unscheduled(milan, SUNDAY, groceries, WorkDetails.completed());

        EarningsResult result = fx.january(milan);

        assertThat(treatmentOf(result, sundayMilk).getLine().getLineType()).isEqualTo(EarningLineType.PER_UNIT);
        assertThat(treatmentOf(result, sundayMilk).getLine().getAmountPaise()).isEqualTo(5650L);
        assertThat(treatmentOf(result, sundayGroceries).getLine().getLineType()).as("no quantity: task-rate precedence")
                .isEqualTo(EarningLineType.EXTRA_TASK);
        assertThat(lines(result, EarningLineType.EXTRA_DAY)).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("extra-day rate is configured but was not applied to per-unit"));
        assertThat(result.totalPaise()).isEqualTo(8650L);
    }

    // --- per day (C1) ---

    @Test
    void perDayExtraActivityIsCoveredUnlessAnExtraRateApplies() {
        Worker devi = withMonToSat("Devi", PaymentModel.PER_DAY);
        Worker deviHourly = withMonToSat("Devi two", PaymentModel.PER_DAY);
        for (Worker w : List.of(devi, deviHourly)) {
            fx.rate(w, RateType.PER_DAY, "300", d("2026-01-01"));
        }
        fx.taskRate(devi, RateType.EXTRA_TASK, carWash, "200", d("2026-01-01"));
        fx.rate(deviHourly, RateType.EXTRA_HOUR, "60", d("2026-01-01"));
        WorkSchedule s1 = scheduleService.history(devi.getId()).getFirst();
        WorkSchedule s2 = scheduleService.history(deviHourly.getId()).getFirst();
        fx.scheduled(devi, MONDAY_5, entryOn(s1, MONDAY, housework), WorkDetails.completed());
        WorkRecord wash = fx.unscheduled(devi, MONDAY_5, carWash, WorkDetails.completed());
        WorkRecord covered = fx.unscheduled(devi, MONDAY_5, gardening, WorkDetails.completedMinutes(60));
        fx.scheduled(deviHourly, MONDAY_5, entryOn(s2, MONDAY, housework), WorkDetails.completed());
        WorkRecord hourly = fx.unscheduled(deviHourly, MONDAY_5, gardening, WorkDetails.completedMinutes(60));

        EarningsResult first = fx.january(devi);
        EarningsResult second = fx.january(deviHourly);

        assertThat(lines(first, EarningLineType.PER_DAY)).hasSize(1);
        assertThat(treatmentOf(first, wash).getLine().getLineType()).isEqualTo(EarningLineType.EXTRA_TASK);
        assertThat(typeOf(first, covered)).isEqualTo(TreatmentType.COVERED_BY_DAY_RATE);
        assertThat(first.totalPaise()).isEqualTo(50000L);
        assertThat(treatmentOf(second, hourly).getLine().getLineType()).isEqualTo(EarningLineType.EXTRA_HOUR);
        assertThat(second.totalPaise()).isEqualTo(36000L);
        assertEveryRecordTreatedOnce(first, devi);
        assertEveryRecordTreatedOnce(second, deviHourly);
    }

    @Test
    void perDayDayOffWorkEarnsOneDayLine() {
        Worker devi = withMonToSat("Devi", PaymentModel.PER_DAY);
        fx.rate(devi, RateType.PER_DAY, "300", d("2026-01-01"));
        WorkRecord a = fx.unscheduled(devi, SUNDAY, gardening, WorkDetails.completed());
        WorkRecord b = fx.unscheduled(devi, SUNDAY, ironing, WorkDetails.completed());

        EarningsResult result = fx.january(devi);

        assertThat(linesOn(result, SUNDAY)).singleElement().satisfies(l -> {
            assertThat(l.getLineType()).isEqualTo(EarningLineType.PER_DAY);
            assertThat(l.getAmountPaise()).isEqualTo(30000L);
        });
        assertThat(treatmentOf(result, a).getLine().getId()).isEqualTo(treatmentOf(result, b).getLine().getId());
    }
}
