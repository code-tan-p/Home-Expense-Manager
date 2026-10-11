package com.homeexpense.service;

import static com.homeexpense.service.EarningsFixture.MON_TO_SAT;
import static com.homeexpense.service.EarningsFixture.d;
import static com.homeexpense.service.EarningsFixture.lines;
import static com.homeexpense.service.EarningsFixture.timed;
import static com.homeexpense.service.EarningsFixture.typeOf;
import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.EarningsResult;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.DeductionDecision;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.WorkSchedule;
import com.homeexpense.model.Worker;
import com.homeexpense.model.earnings.EarningLine;
import com.homeexpense.model.earnings.EarningLineType;
import com.homeexpense.model.earnings.EarningRecordTreatment;
import com.homeexpense.model.earnings.ReviewDecisionKind;
import com.homeexpense.model.earnings.ReviewStatus;
import com.homeexpense.model.earnings.TreatmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Monthly salary: proration by scheduled day-units, unpaid time, and review of partial work (₹6000 a month). */
@Transactional
class EarningsSalaryIntegrationTest extends SqliteIntegrationTest {

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
    private Task housework;

    @BeforeEach
    void setUp() {
        fx = new EarningsFixture(workerService, catalogService, scheduleService, payService, dailyWorkService, earningsService);
        housework = fx.task("Housework");
    }

    /** Monthly-salary worker with Monday-Saturday housework 07:00-09:00 and ₹6000 from 2026-01-01. */
    private Worker salaried(String name, String start) {
        Worker worker = fx.worker(name, d(start), PaymentModel.MONTHLY_SALARY);
        fx.weekly(worker, d("2026-01-01"), MON_TO_SAT, day -> List.of(timed(day, housework, "07:00", "09:00")));
        fx.rate(worker, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));
        return worker;
    }

    private WorkSchedule scheduleOf(Worker worker) {
        return scheduleService.history(worker.getId()).getFirst();
    }

    private static EarningLine salaryLine(EarningsResult result) {
        return lines(result, EarningLineType.MONTHLY_SALARY).getFirst();
    }

    @Test
    void fullMonthPaysTheAgreedSalaryAndListsUnrecordedDays() {
        Worker sunita = salaried("Sunita", "2026-01-01");

        EarningsResult result = fx.january(sunita);

        EarningLine salary = salaryLine(result);
        assertThat(salary.getAmountPaise()).isEqualTo(600000L);
        assertThat(salary.getPaidDayUnits()).isEqualTo("27");
        assertThat(salary.getScheduledDayUnits()).isEqualTo(27);
        assertThat(result.totalPaise()).isEqualTo(600000L);
        assertThat(result.unrecorded()).hasSize(27);
        assertThat(result.treatments()).as("no work is assumed done").isEmpty();
        assertThat(result.blockers()).anyMatch(b -> b.startsWith("27 scheduled activities are not recorded"));
    }

    @Test
    void employmentStartingMidMonthIsProratedByScheduledDays() {
        Worker asha = salaried("Asha", "2026-01-13");

        EarningLine salary = salaryLine(fx.january(asha));

        assertThat(salary.getPaidDayUnits()).isEqualTo("17");
        assertThat(salary.getExactPaise()).isEqualTo("3400000/9");
        assertThat(salary.getAmountPaise()).as("600000 x 17 / 27 = 377777.78, rounded half-up once").isEqualTo(377778L);
        assertThat(salary.getExplanation()).contains("17", "27 scheduled day-units", "₹3777.78");
    }

    @Test
    void employmentEndingMidMonthIsProrated() {
        Worker sunita = salaried("Sunita", "2026-01-01");
        workerService.deactivate(sunita.getId(), d("2026-01-15"), null);

        assertThat(salaryLine(fx.january(sunita)).getAmountPaise()).as("600000 x 13 / 27").isEqualTo(288889L);
    }

    @Test
    void salaryRateChangeMidMonthSplitsIntoSegments() {
        Worker sunita = salaried("Sunita", "2026-01-01");
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6600", d("2026-01-16"));

        EarningsResult result = fx.january(sunita);

        assertThat(lines(result, EarningLineType.MONTHLY_SALARY)).extracting(EarningLine::getRateAmountPaise, EarningLine::getPaidDayUnits,
                EarningLine::getAmountPaise).containsExactly(
                org.assertj.core.groups.Tuple.tuple(600000L, "13", 288889L),
                org.assertj.core.groups.Tuple.tuple(660000L, "14", 342222L));
        assertThat(result.totalPaise()).isEqualTo(631111L);
    }

    @Test
    void scheduleChangeMidMonthChangesTheDenominatorDayByDay() {
        Worker asha = salaried("Asha", "2026-01-13");
        fx.weekly(asha, d("2026-01-16"), MON_TO_SAT.subList(0, 5), day -> List.of(timed(day, housework, "07:00", "09:00")));

        EarningLine salary = salaryLine(fx.january(asha));

        assertThat(salary.getScheduledDayUnits()).isEqualTo(24);
        assertThat(salary.getPaidDayUnits()).isEqualTo("14");
        assertThat(salary.getAmountPaise()).isEqualTo(350000L);
    }

    @Test
    void approvedLeaveIsUnpaid() {
        Worker sunita = salaried("Sunita", "2026-01-01");
        WorkRecord leave = fx.scheduled(sunita, "2026-01-05", EarningsFixture.entryOn(scheduleOf(sunita), MONDAY, housework),
                WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE));

        EarningsResult result = fx.january(sunita);

        assertThat(salaryLine(result).getPaidDayUnits()).isEqualTo("26");
        assertThat(result.totalPaise()).isEqualTo(577778L);
        assertThat(typeOf(result, leave)).isEqualTo(TreatmentType.UNPAID_LEAVE);
    }

    @Test
    void unauthorizedAbsenceReducesSalaryOnlyWhenTheDeductionIsApproved() {
        Worker pending = salaried("Pending", "2026-01-01");
        Worker approved = salaried("Approved", "2026-01-01");
        Worker waived = salaried("Waived", "2026-01-01");
        WorkRecord p = fx.scheduled(pending, "2026-01-05", EarningsFixture.entryOn(scheduleOf(pending), MONDAY, housework),
                WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));
        WorkRecord a = fx.scheduled(approved, "2026-01-05", EarningsFixture.entryOn(scheduleOf(approved), MONDAY, housework),
                WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));
        WorkRecord w = fx.scheduled(waived, "2026-01-05", EarningsFixture.entryOn(scheduleOf(waived), MONDAY, housework),
                WorkDetails.notDone(AbsenceReason.UNAUTHORIZED_ABSENCE));
        dailyWorkService.decideDeduction(a.getId(), DeductionDecision.APPROVED, null);
        dailyWorkService.decideDeduction(w.getId(), DeductionDecision.WAIVED, null);

        EarningsResult pendingResult = fx.january(pending);
        EarningsResult approvedResult = fx.january(approved);
        EarningsResult waivedResult = fx.january(waived);

        assertThat(pendingResult.totalPaise()).isEqualTo(600000L);
        assertThat(typeOf(pendingResult, p)).isEqualTo(TreatmentType.ABSENCE_PENDING_PAID);
        assertThat(pendingResult.warnings()).anyMatch(warning -> warning.contains("has no deduction decision"));
        assertThat(pendingResult.blockers()).noneMatch(blocker -> blocker.contains("deduction"));

        assertThat(approvedResult.totalPaise()).isEqualTo(577778L);
        assertThat(typeOf(approvedResult, a)).isEqualTo(TreatmentType.ABSENCE_APPROVED_UNPAID);

        assertThat(waivedResult.totalPaise()).isEqualTo(600000L);
        assertThat(typeOf(waivedResult, w)).isEqualTo(TreatmentType.ABSENCE_WAIVED_PAID);
    }

    @Test
    void householdHolidayOnAScheduledDayIsPaid() {
        Worker sunita = salaried("Sunita", "2026-01-01");
        WorkRecord holiday = fx.scheduled(sunita, "2026-01-05", EarningsFixture.entryOn(scheduleOf(sunita), MONDAY, housework),
                WorkDetails.notDone(AbsenceReason.HOUSEHOLD_HOLIDAY));
        dailyWorkService.markWholeDay(sunita.getId(), d("2026-01-04"), AbsenceReason.HOUSEHOLD_HOLIDAY, null);

        EarningsResult result = fx.january(sunita);

        assertThat(result.totalPaise()).isEqualTo(600000L);
        assertThat(typeOf(result, holiday)).isEqualTo(TreatmentType.HOLIDAY_PAID);
        assertThat(result.treatments()).as("a holiday on a regular day off creates nothing").hasSize(1);
    }

    @Test
    void partialDayLeaveIsWeightedByScheduledMinutes() {
        Task cooking = fx.task("Cooking");
        Worker sunita = fx.worker("Sunita", d("2026-01-01"), PaymentModel.MONTHLY_SALARY);
        WorkSchedule schedule = fx.weekly(sunita, d("2026-01-01"), MON_TO_SAT, day -> List.of(
                timed(day, housework, "07:00", "08:00"), timed(day, cooking, "08:00", "10:00")));
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));
        fx.scheduled(sunita, "2026-01-05", EarningsFixture.entryOn(schedule, MONDAY, housework), WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE));

        EarningLine salary = salaryLine(fx.january(sunita));

        assertThat(salary.getPaidDayUnits()).as("27 - 60/180").isEqualTo("80/3");
        assertThat(salary.getAmountPaise()).as("600000 x 80/3 / 27 = 592592.59").isEqualTo(592593L);
    }

    @Test
    void partialDayLeaveFallsBackToActivityCountWithoutMinutes() {
        Task cooking = fx.task("Cooking");
        Worker sunita = fx.worker("Sunita", d("2026-01-01"), PaymentModel.MONTHLY_SALARY);
        WorkSchedule schedule = fx.weekly(sunita, d("2026-01-01"), MON_TO_SAT, day -> List.of(
                NewScheduleEntry.task(day, housework.getId()), NewScheduleEntry.task(day, cooking.getId())));
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));
        fx.scheduled(sunita, "2026-01-05", EarningsFixture.entryOn(schedule, MONDAY, housework), WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE));

        EarningLine salary = salaryLine(fx.january(sunita));

        assertThat(salary.getPaidDayUnits()).isEqualTo("53/2");
        assertThat(salary.getAmountPaise()).isEqualTo(588889L);
    }

    @Test
    void partialWorkSuggestsAReductionThatOnlyAppliesOnceAccepted() {
        Task cooking = fx.task("Cooking");
        Worker sunita = fx.worker("Sunita", d("2026-01-01"), PaymentModel.MONTHLY_SALARY);
        WorkSchedule schedule = fx.weekly(sunita, d("2026-01-01"), MON_TO_SAT, day -> List.of(
                timed(day, housework, "07:00", "08:00"), timed(day, cooking, "08:00", "10:00")));
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));
        WorkRecord partial = fx.scheduled(sunita, "2026-01-05", EarningsFixture.entryOn(schedule, MONDAY, cooking), WorkDetails.partialMinutes(60));

        EarningsResult pendingResult = fx.january(sunita);
        EarningRecordTreatment review = EarningsFixture.treatmentOf(pendingResult, partial);
        assertThat(review.getTreatment()).isEqualTo(TreatmentType.SALARY_PARTIAL_REDUCTION);
        assertThat(review.getReviewStatus()).isEqualTo(ReviewStatus.NEEDS_REVIEW);
        assertThat(review.getSuggestedValue()).as("(120/180) x (1 - 60/120)").isEqualTo("1/3");
        assertThat(pendingResult.totalPaise()).as("not applied automatically").isEqualTo(600000L);
        assertThat(pendingResult.run().getNeedsReviewCount()).isEqualTo(1);
        assertThat(pendingResult.lines()).allMatch(l -> l.getAmountPaise() == null || l.getAmountPaise() >= 0);

        EarningsResult accepted = earningsService.reviewSalaryPartial(review.getId(), ReviewDecisionKind.ACCEPT_SUGGESTED, null, null);
        assertThat(accepted.totalPaise()).isEqualTo(592593L);
        assertThat(accepted.run().getNeedsReviewCount()).isZero();

        EarningRecordTreatment again = EarningsFixture.treatmentOf(accepted, partial);
        EarningsResult overridden = earningsService.reviewSalaryPartial(again.getId(), ReviewDecisionKind.OVERRIDE, "0.5", "Agreed half a day");
        assertThat(overridden.totalPaise()).isEqualTo(588889L);
        assertThat(EarningsFixture.treatmentOf(overridden, partial).getReviewStatus()).isEqualTo(ReviewStatus.OVERRIDDEN);
    }

    @Test
    void salaryReductionOverrideMustStayWithinTheActivitysShare() {
        Task cooking = fx.task("Cooking");
        Worker sunita = fx.worker("Sunita", d("2026-01-01"), PaymentModel.MONTHLY_SALARY);
        WorkSchedule schedule = fx.weekly(sunita, d("2026-01-01"), MON_TO_SAT, day -> List.of(
                timed(day, housework, "07:00", "08:00"), timed(day, cooking, "08:00", "10:00")));
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));
        WorkRecord partial = fx.scheduled(sunita, "2026-01-05", EarningsFixture.entryOn(schedule, MONDAY, cooking), WorkDetails.partialMinutes(60));
        Long treatmentId = EarningsFixture.treatmentOf(fx.january(sunita), partial).getId();

        assertThatThrownBy(() -> earningsService.reviewSalaryPartial(treatmentId, ReviewDecisionKind.OVERRIDE, "0.7", "Too much"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("between 0 and 0.6667");
        assertThatThrownBy(() -> earningsService.reviewSalaryPartial(treatmentId, ReviewDecisionKind.OVERRIDE, "0.2", " "))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("reason");
    }

    @Test
    void partialWorkWithoutAMinutesBaselineNeedsAManualDecision() {
        Task cooking = fx.task("Cooking");
        Worker sunita = fx.worker("Sunita", d("2026-01-01"), PaymentModel.MONTHLY_SALARY);
        WorkSchedule schedule = fx.weekly(sunita, d("2026-01-01"), MON_TO_SAT, day -> List.of(
                NewScheduleEntry.task(day, housework.getId()), NewScheduleEntry.task(day, cooking.getId())));
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));
        WorkRecord partial = fx.scheduled(sunita, "2026-01-05", EarningsFixture.entryOn(schedule, MONDAY, cooking),
                new WorkDetails(com.homeexpense.model.WorkOutcome.PARTIAL, null, null, null, null, null, "Left early"));

        EarningRecordTreatment review = EarningsFixture.treatmentOf(fx.january(sunita), partial);

        assertThat(review.getSuggestedValue()).isNull();
        assertThat(review.getReviewStatus()).isEqualTo(ReviewStatus.NEEDS_REVIEW);
        assertThatThrownBy(() -> earningsService.reviewSalaryPartial(review.getId(), ReviewDecisionKind.ACCEPT_SUGGESTED, null, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("no reliable suggestion");
        assertThat(earningsService.reviewSalaryPartial(review.getId(), ReviewDecisionKind.OVERRIDE, "0.25", "Left after a quarter")
                .totalPaise()).as("600000 x 26.75 / 27").isEqualTo(594444L);
    }

    @Test
    void salaryWithoutAnyScheduleIsBlocked() {
        Worker sunita = fx.worker("Sunita", d("2026-01-01"), PaymentModel.MONTHLY_SALARY);
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));

        EarningsResult result = fx.january(sunita);

        assertThat(result.lines()).isEmpty();
        assertThat(result.blockers()).anyMatch(b -> b.contains("no scheduled working days"));
    }

    @Test
    void missingSalaryRateIsBlocked() {
        Worker sunita = fx.worker("Sunita", d("2026-01-01"), PaymentModel.MONTHLY_SALARY);
        fx.weekly(sunita, d("2026-01-01"), MON_TO_SAT, day -> List.of(timed(day, housework, "07:00", "09:00")));

        assertThat(fx.january(sunita).blockers()).anyMatch(b -> b.contains("No monthly salary rate applies"));
    }

    @Test
    void leapYearFebruaryIsProratedCorrectly() {
        Worker asha = salaried("Asha", "2028-02-15");

        EarningLine salary = salaryLine(earningsService.calculate(asha.getId(), YearMonth.of(2028, 2)));

        assertThat(salary.getScheduledDayUnits()).isEqualTo(25);
        assertThat(salary.getPaidDayUnits()).isEqualTo("13");
        assertThat(salary.getAmountPaise()).isEqualTo(312000L);
    }
}
