package com.homeexpense.service;

import static com.homeexpense.service.EarningsFixture.JANUARY;
import static com.homeexpense.service.EarningsFixture.MON_TO_SAT;
import static com.homeexpense.service.EarningsFixture.d;
import static com.homeexpense.service.EarningsFixture.entryOn;
import static com.homeexpense.service.EarningsFixture.pending;
import static com.homeexpense.service.EarningsFixture.timed;
import static com.homeexpense.service.EarningsFixture.treatmentOf;
import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.util.List;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.EarningsResult;
import com.homeexpense.dto.NewPayRate;
import com.homeexpense.dto.NewScheduleEntry;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AuditEvent;
import com.homeexpense.model.PayRate;
import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.RateType;
import com.homeexpense.model.Task;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.WorkSchedule;
import com.homeexpense.model.Worker;
import com.homeexpense.model.earnings.EarningLine;
import com.homeexpense.model.earnings.EarningRunStatus;
import com.homeexpense.model.earnings.ReviewDecisionKind;
import com.homeexpense.model.earnings.ReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Recalculation, reviews, corrections, unrecorded-day confirmations, finalizing and locks. */
@Transactional
class EarningsLifecycleIntegrationTest extends SqliteIntegrationTest {

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
    private AuditService auditService;
    @Autowired
    private JdbcTemplate jdbc;

    private EarningsFixture fx;
    private Task mopping;
    private Worker ravi;
    private WorkSchedule ravisSchedule;
    private PayRate ravisRate;

    /** Ravi: per task, mopping 10:00-11:00 on Mondays only (5th, 12th, 19th, 26th of January), ₹50 a task. */
    @BeforeEach
    void setUp() {
        fx = new EarningsFixture(workerService, catalogService, scheduleService, payService, dailyWorkService, earningsService);
        mopping = fx.task("Mopping");
        ravi = fx.worker("Ravi", d("2026-01-01"), PaymentModel.PER_TASK);
        ravisSchedule = scheduleService.addScheduleVersion(ravi.getId(), d("2026-01-01"),
                List.of(timed(MONDAY, mopping, "10:00", "11:00")), null);
        ravisRate = fx.rate(ravi, RateType.PER_TASK, "50", d("2026-01-01"));
    }

    private WorkRecord mop(String date, WorkDetails details) {
        return fx.scheduled(ravi, date, entryOn(ravisSchedule, MONDAY, mopping), details);
    }

    private void recordAllMondays() {
        for (String monday : List.of("2026-01-05", "2026-01-12", "2026-01-19", "2026-01-26")) {
            mop(monday, WorkDetails.completed());
        }
    }

    // --- idempotency ---

    @Test
    void recalculatingUnchangedDataChangesNothing() {
        recordAllMondays();

        EarningsResult first = fx.january(ravi);
        EarningsResult second = fx.january(ravi);
        EarningsResult third = fx.january(ravi);

        assertThat(List.of(first.totalPaise(), second.totalPaise(), third.totalPaise())).containsOnly(20000L);
        assertThat(third.run().getId()).isEqualTo(first.run().getId());
        assertThat(third.lines()).extracting(EarningLine::getExplanation)
                .containsExactlyElementsOf(first.lines().stream().map(EarningLine::getExplanation).toList());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM earning_line WHERE run_id = ?", Integer.class, third.run().getId())).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM earning_record_treatment WHERE run_id = ?", Integer.class, third.run().getId())).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM earning_run WHERE worker_id = ?", Integer.class, ravi.getId())).isEqualTo(1);
        List<AuditEvent> calculations = auditService.historyForWorker(ravi.getId()).stream()
                .filter(e -> e.getEntityType().equals(EarningsService.ENTITY_RUN)).toList();
        assertThat(calculations).hasSize(3);
        assertThat(calculations.get(0).getDetails()).contains("previousTotal=none", "total=₹200.00");
        assertThat(calculations.get(2).getDetails()).contains("previousTotal=₹200.00", "total=₹200.00");
    }

    @Test
    void recalculationPicksUpNewWork() {
        mop("2026-01-05", WorkDetails.completed());
        assertThat(fx.january(ravi).totalPaise()).isEqualTo(5000L);

        mop("2026-01-12", WorkDetails.completed());
        assertThat(fx.january(ravi).totalPaise()).isEqualTo(10000L);
    }

    // --- corrections and voids ---

    @Test
    void correctedAndVoidedRecordsAreNeverPaid() {
        WorkRecord original = mop("2026-01-05", WorkDetails.completed());
        WorkRecord mistaken = mop("2026-01-12", WorkDetails.completed());
        fx.january(ravi);

        WorkRecord replacement = dailyWorkService.correct(original.getId(), WorkDetails.partialMinutes(30), "Left early", false).record();
        dailyWorkService.voidRecord(mistaken.getId(), "Wrong week");
        EarningsResult result = fx.january(ravi);

        assertThat(result.treatments()).extracting(t -> t.getWorkRecord().getId()).containsExactly(replacement.getId());
        assertThat(treatmentOf(result, replacement).getLine().getSuggestedAmountPaise()).isEqualTo(2500L);
        assertThat(result.totalPaise()).as("the partial amount waits for review").isZero();
    }

    @Test
    void aReviewStopsApplyingWhenTheRecordIsCorrected() {
        WorkRecord partial = mop("2026-01-05", WorkDetails.partialMinutes(30));
        EarningLine line = treatmentOf(fx.january(ravi), partial).getLine();
        EarningsResult accepted = earningsService.reviewLine(line.getId(), ReviewDecisionKind.ACCEPT_SUGGESTED, null, null);
        assertThat(accepted.totalPaise()).isEqualTo(2500L);

        WorkRecord corrected = dailyWorkService.correct(partial.getId(), WorkDetails.partialMinutes(45), "Stayed longer", false).record();
        EarningsResult result = fx.january(ravi);

        EarningLine newLine = treatmentOf(result, corrected).getLine();
        assertThat(newLine.getReviewStatus()).isEqualTo(ReviewStatus.NEEDS_REVIEW);
        assertThat(newLine.getSuggestedAmountPaise()).isEqualTo(3750L);
        assertThat(result.totalPaise()).isZero();
    }

    @Test
    void aReviewStopsApplyingWhenTheSuggestionChanges() {
        WorkRecord partial = mop("2026-01-05", WorkDetails.partialMinutes(30));
        EarningLine line = treatmentOf(fx.january(ravi), partial).getLine();
        earningsService.reviewLine(line.getId(), ReviewDecisionKind.ACCEPT_SUGGESTED, null, null);

        payService.correctRateAmount(ravisRate.getId(), "55", "Agreed rate was ₹55");
        EarningsResult result = fx.january(ravi);

        assertThat(pending(result)).singleElement().satisfies(l -> assertThat(l.getSuggestedAmountPaise()).isEqualTo(2750L));
    }

    @Test
    void reviewRules() {
        WorkRecord done = mop("2026-01-05", WorkDetails.completed());
        WorkRecord partial = mop("2026-01-12", WorkDetails.partialMinutes(30));
        EarningsResult result = fx.january(ravi);
        Long fixedLine = treatmentOf(result, done).getLine().getId();
        Long reviewLine = treatmentOf(result, partial).getLine().getId();

        assertThatThrownBy(() -> earningsService.reviewLine(fixedLine, ReviewDecisionKind.ACCEPT_SUGGESTED, null, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("does not need review");
        assertThatThrownBy(() -> earningsService.reviewLine(reviewLine, ReviewDecisionKind.OVERRIDE, "20.005", "x"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("more than two decimal places");
        assertThatThrownBy(() -> earningsService.reviewLine(reviewLine, ReviewDecisionKind.OVERRIDE, "20", ""))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("reason");

        EarningsResult overridden = earningsService.reviewLine(reviewLine, ReviewDecisionKind.OVERRIDE, "30", "Half the job, agreed ₹30");
        assertThat(overridden.totalPaise()).isEqualTo(8000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM earning_review WHERE worker_id = ?", Integer.class, ravi.getId())).isEqualTo(1);
    }

    // --- unrecorded activities (F1 / C4) ---

    @Test
    void salaryConfirmationListsTheDatesAndExpiresWhenTheSetChanges() {
        Task housework = fx.task("Housework");
        Worker sunita = fx.worker("Sunita", d("2026-01-01"), PaymentModel.MONTHLY_SALARY);
        WorkSchedule s = fx.weekly(sunita, d("2026-01-01"), MON_TO_SAT, day -> List.of(timed(day, housework, "07:00", "09:00")));
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));

        EarningsResult confirmed = earningsService.confirmUnrecorded(sunita.getId(), JANUARY);

        assertThat(confirmed.blockers()).isEmpty();
        assertThat(confirmed.totalPaise()).as("no reduction for missing records").isEqualTo(600000L);
        String statement = jdbc.queryForObject("SELECT statement FROM earning_unrecorded_confirmation WHERE worker_id = ?", String.class, sunita.getId());
        assertThat(statement).startsWith(EarningsService.SALARY_STATEMENT)
                .contains("2026-01-05 (MONDAY): Housework at 07:00").contains("2026-01-31 (SATURDAY)");
        assertThat(auditService.historyForWorker(sunita.getId())).anyMatch(e -> e.getAction().equals("CONFIRM_UNRECORDED"));

        fx.scheduled(sunita, "2026-01-05", entryOn(s, MONDAY, housework), WorkDetails.completed());
        EarningsResult changed = fx.january(sunita);

        assertThat(changed.blockers()).anyMatch(b -> b.contains("26 scheduled activities are not recorded") && b.contains("has expired"));
    }

    @Test
    void nonSalaryConfirmationUsesItsOwnWording() {
        mop("2026-01-05", WorkDetails.completed());

        earningsService.confirmUnrecorded(ravi.getId(), JANUARY);

        String statement = jdbc.queryForObject("SELECT statement FROM earning_unrecorded_confirmation WHERE worker_id = ?", String.class, ravi.getId());
        assertThat(statement).startsWith(EarningsService.OTHER_STATEMENT).contains("2026-01-12", "2026-01-19", "2026-01-26")
                .doesNotContain("2026-01-05");
        assertThat(fx.january(ravi).totalPaise()).as("unrecorded activities earn nothing").isEqualTo(5000L);
    }

    @Test
    void nothingToConfirmWhenEverythingIsRecorded() {
        recordAllMondays();

        assertThatThrownBy(() -> earningsService.confirmUnrecorded(ravi.getId(), JANUARY))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("nothing to confirm");
    }

    // --- finalizing ---

    @Test
    void finalizingRequiresAnEndedMonthNoBlockersAndTheReviewedTotal() {
        assertThatThrownBy(() -> earningsService.finalizeMonth(ravi.getId(), JANUARY, 0))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("first");
        mop("2026-01-05", WorkDetails.completed());
        fx.january(ravi);
        assertThatThrownBy(() -> earningsService.finalizeMonth(ravi.getId(), JANUARY, 5000))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be finalized yet").hasMessageContaining("not recorded");

        for (String monday : List.of("2026-01-12", "2026-01-19", "2026-01-26")) {
            mop(monday, WorkDetails.completed());
        }
        assertThatThrownBy(() -> earningsService.finalizeMonth(ravi.getId(), JANUARY, 5000))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("total changed from ₹50.00 to ₹200.00");

        YearMonth current = YearMonth.now();
        earningsService.calculate(ravi.getId(), current);
        assertThatThrownBy(() -> earningsService.finalizeMonth(ravi.getId(), current, 0))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("after the month ends");

        EarningsResult finalized = earningsService.finalizeMonth(ravi.getId(), JANUARY, 20000);
        assertThat(finalized.run().getStatus()).isEqualTo(EarningRunStatus.FINALIZED);
        assertThat(finalized.run().getFinalizedAt()).isNotNull();
        assertThat(auditService.historyForWorker(ravi.getId())).anyMatch(e -> e.getAction().equals("FINALIZE"));
    }

    @Test
    void pendingReviewBlocksFinalizing() {
        mop("2026-01-05", WorkDetails.completed());
        mop("2026-01-12", WorkDetails.completed());
        mop("2026-01-19", WorkDetails.completed());
        mop("2026-01-26", WorkDetails.partialMinutes(30));
        long total = fx.january(ravi).totalPaise();

        assertThatThrownBy(() -> earningsService.finalizeMonth(ravi.getId(), JANUARY, total))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("needs your review");
    }

    @Test
    void finalizedEarningsLockTheirDatesEverywhere() {
        recordAllMondays();
        WorkRecord first = dailyWorkService.history(ravi.getId(), d("2026-01-05"), d("2026-01-05")).getFirst();
        earningsService.calculate(ravi.getId(), JANUARY);
        earningsService.finalizeMonth(ravi.getId(), JANUARY, 20000);

        assertThatThrownBy(() -> earningsService.calculate(ravi.getId(), JANUARY))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("finalized and cannot be recalculated");
        assertThatThrownBy(() -> dailyWorkService.recordUnscheduled(ravi.getId(), d("2026-01-20"), mopping.getId(), null,
                WorkDetails.completed(), true))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Earnings have already been worked out");
        assertThatThrownBy(() -> dailyWorkService.correct(first.getId(), WorkDetails.partialMinutes(30), "Late correction", false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be changed here");
        assertThatThrownBy(() -> dailyWorkService.voidRecord(first.getId(), "Mistake"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be changed here");
        assertThatThrownBy(() -> payService.addRate(NewPayRate.standard(ravi.getId(), RateType.PER_TASK, "60", d("2026-01-20"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("earnings recorded on or after 2026-01-20");
        assertThatThrownBy(() -> payService.correctRateAmount(ravisRate.getId(), "60", "Late"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be corrected in place");
        assertThatThrownBy(() -> scheduleService.addScheduleVersion(ravi.getId(), d("2026-01-20"), List.of(), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("earnings recorded on or after 2026-01-20");
        assertThatThrownBy(() -> workerService.deactivate(ravi.getId(), d("2026-01-20"), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("has earnings recorded after 2026-01-20");

        // February is still open.
        assertThat(mop("2026-02-02", WorkDetails.completed())).isNotNull();
        assertThat(payService.addRate(NewPayRate.standard(ravi.getId(), RateType.PER_TASK, "60", d("2026-02-01")))).isNotNull();
    }

    @Test
    void salariedMonthCanBeFinalizedWithAConfirmation() {
        Task housework = fx.task("Housework");
        Worker sunita = fx.worker("Sunita", d("2026-01-01"), PaymentModel.MONTHLY_SALARY);
        fx.weekly(sunita, d("2026-01-01"), MON_TO_SAT, day -> List.of(NewScheduleEntry.task(day, housework.getId())));
        fx.rate(sunita, RateType.MONTHLY_SALARY, "6000", d("2026-01-01"));
        earningsService.confirmUnrecorded(sunita.getId(), JANUARY);

        EarningsResult finalized = earningsService.finalizeMonth(sunita.getId(), JANUARY, 600000);

        assertThat(finalized.run().getStatus()).isEqualTo(EarningRunStatus.FINALIZED);
        assertThat(finalized.warnings()).anyMatch(w -> w.contains("left unrecorded, confirmed"));
    }
}
