package com.homeexpense.service;

import static com.homeexpense.service.FinanceFixture.FEB;
import static com.homeexpense.service.FinanceFixture.JAN;
import static com.homeexpense.service.FinanceFixture.MAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.FinanceDtos.OutstandingChoice;
import com.homeexpense.dto.FinanceDtos.OverpaymentChoice;
import com.homeexpense.dto.FinanceDtos.SettleRequest;
import com.homeexpense.dto.FinanceDtos.SettlementView;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import com.homeexpense.model.earnings.EarningRun;
import com.homeexpense.model.finance.AdvanceSource;
import com.homeexpense.model.finance.PaymentMethod;
import com.homeexpense.model.finance.SettleTreatment;
import com.homeexpense.model.finance.SettlementStatus;
import com.homeexpense.repository.EarningRunRepository;
import com.homeexpense.repository.SettlementVersionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Reopening earnings and settlements (D8), overpayment (D9, Q6) and carry corrections (U2). */
@Transactional
class ReopenIntegrationTest extends SqliteIntegrationTest {

    private static final LocalDate JAN_26 = LocalDate.of(2026, 1, 26);

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
    private SettlementService settlementService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private EarningRunRepository runRepository;
    @Autowired
    private SettlementVersionRepository versionRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    private FinanceFixture fx;
    private Worker ravi;

    @BeforeEach
    void setUp() {
        EarningsFixture earnings = new EarningsFixture(workerService, catalogService, scheduleService, payService, dailyWorkService,
                earningsService);
        fx = new FinanceFixture(earnings, settlementService, paymentService, "Mopping");
        ravi = fx.worker("Ravi", "50");
    }

    /** Turns Monday 26 January into approved leave, so January earns ₹150 instead of ₹200. */
    private void correctJanuaryToLeave() {
        WorkRecord record = dailyWorkService.history(ravi.getId(), JAN_26, JAN_26).stream().filter(WorkRecord::isCurrent).findFirst().orElseThrow();
        dailyWorkService.correct(record.getId(), WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE), "It was approved leave", false);
    }

    /** Turns the given January Mondays into approved leave (each is ₹50 less). */
    private void leaveOn(int... days) {
        for (int day : days) {
            LocalDate date = LocalDate.of(2026, 1, day);
            WorkRecord record = dailyWorkService.history(ravi.getId(), date, date).stream().filter(WorkRecord::isCurrent).findFirst().orElseThrow();
            dailyWorkService.correct(record.getId(), WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE), "Approved leave", false);
        }
    }

    private void payOn(LocalDate date, String amount) {
        paymentService.record(new com.homeexpense.dto.FinanceDtos.RecordPayment(ravi.getId(), date, amount, PaymentMethod.CASH, null, null,
                null, null));
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        entityManager.flush();
        return jdbc.queryForList(sql, args);
    }

    private long count(String sql, Object... args) {
        entityManager.flush();
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // --- reopening earnings (no settlement or an open one) ---

    @Test
    void finalizedEarningsCanBeReopenedWithoutASettlement() {
        fx.finalizeMonth(ravi, JAN);
        fx.finalizeMonth(ravi, FEB);
        EarningRun original = runRepository.findActive(ravi.getId(), JAN.atDay(1)).orElseThrow();
        List<Map<String, Object>> originalRun = rows("SELECT * FROM earning_run WHERE id = ?", original.getId());
        List<Map<String, Object>> originalLines = rows("SELECT * FROM earning_line WHERE run_id = ? ORDER BY id", original.getId());

        assertThatThrownBy(() -> earningsService.reopenEarnings(ravi.getId(), JAN, " "))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("reason");
        earningsService.reopenEarnings(ravi.getId(), JAN, "Jan 26 was leave");

        correctJanuaryToLeave();
        assertThatThrownBy(() -> dailyWorkService.recordUnscheduled(ravi.getId(), LocalDate.of(2026, 2, 3), fx.mopping.getId(), null,
                WorkDetails.completed(), true))
                .as("February is still finalized and locked").isInstanceOf(BusinessRuleException.class);

        long total = fx.finalizeMonth(ravi, JAN);
        assertThat(total).isEqualTo(15000L);
        assertThat(rows("SELECT * FROM earning_run WHERE id = ?", original.getId())).isEqualTo(originalRun);
        assertThat(rows("SELECT * FROM earning_line WHERE run_id = ? ORDER BY id", original.getId())).isEqualTo(originalLines);
        EarningRun replacement = runRepository.findActive(ravi.getId(), JAN.atDay(1)).orElseThrow();
        assertThat(rows("SELECT replacement_run_id FROM earning_run_supersession WHERE superseded_run_id = ?", original.getId()))
                .singleElement().satisfies(r -> assertThat(r.get("replacement_run_id")).isEqualTo(replacement.getId().intValue()));
    }

    @Test
    void reopeningEarningsSupersedesAnOpenSettlementButRefusesASettledOne() {
        SettlementView open = fx.finalizeAndPrepare(ravi, JAN);

        earningsService.reopenEarnings(ravi.getId(), JAN, "Recheck January");

        assertThat(versionRepository.findById(open.version().getId()).orElseThrow().getStatus()).isEqualTo(SettlementStatus.SUPERSEDED);
        assertThat(fx.finalizeAndPrepare(ravi, JAN).version().getVersionNo()).isEqualTo(2);
        fx.pay(ravi, "200");
        fx.settle(ravi, JAN);
        assertThatThrownBy(() -> earningsService.reopenEarnings(ravi.getId(), JAN, "Again"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Reopen the settlement");
    }

    // --- reopening a settled month (D8) and overpayment (D9) ---

    @Test
    void reopeningASettledMonthKeepsEveryHistoricalRecord() {
        fx.finalizeAndPrepare(ravi, JAN);
        fx.pay(ravi, "200");
        SettlementView settled = fx.settle(ravi, JAN);
        EarningRun oldRun = runRepository.findActive(ravi.getId(), JAN.atDay(1)).orElseThrow();
        Map<String, List<Map<String, Object>>> before = Map.of(
                "items", rows("SELECT * FROM settlement_item WHERE version_id = ? ORDER BY id", settled.version().getId()),
                "payments", rows("SELECT * FROM payment WHERE worker_id = ? ORDER BY id", ravi.getId()),
                "allocations", rows("SELECT * FROM payment_allocation ORDER BY id"),
                "run", rows("SELECT * FROM earning_run WHERE id = ?", oldRun.getId()),
                "lines", rows("SELECT * FROM earning_line WHERE run_id = ? ORDER BY id", oldRun.getId()),
                "treatments", rows("SELECT * FROM earning_record_treatment WHERE run_id = ? ORDER BY id", oldRun.getId()));
        Map<String, Object> versionBefore = rows("SELECT * FROM settlement_version WHERE id = ?", settled.version().getId()).getFirst();
        long auditBefore = count("SELECT COUNT(*) FROM audit_event");

        settlementService.reopenSettlement(ravi.getId(), JAN, "Jan 26 was actually leave");
        correctJanuaryToLeave();
        fx.finalizeMonth(ravi, JAN);
        SettlementView second = fx.prepare(ravi, JAN);

        assertThat(second.version().getVersionNo()).isEqualTo(2);
        assertThat(second.version().getNetPayablePaise()).isEqualTo(15000L);
        assertThat(second.allocatedPaise()).as("the payment still counts, unchanged").isEqualTo(20000L);
        assertThat(second.overpaymentPaise()).isEqualTo(5000L);
        before.forEach((name, rowsBefore) -> {
            String sql = switch (name) {
                case "items" -> "SELECT * FROM settlement_item WHERE version_id = " + settled.version().getId() + " ORDER BY id";
                case "payments" -> "SELECT * FROM payment WHERE worker_id = " + ravi.getId() + " ORDER BY id";
                case "allocations" -> "SELECT * FROM payment_allocation ORDER BY id";
                case "run" -> "SELECT * FROM earning_run WHERE id = " + oldRun.getId();
                case "lines" -> "SELECT * FROM earning_line WHERE run_id = " + oldRun.getId() + " ORDER BY id";
                default -> "SELECT * FROM earning_record_treatment WHERE run_id = " + oldRun.getId() + " ORDER BY id";
            };
            assertThat(rows(sql)).as(name + " unchanged").isEqualTo(rowsBefore);
        });
        Map<String, Object> versionAfter = rows("SELECT * FROM settlement_version WHERE id = ?", settled.version().getId()).getFirst();
        assertThat(versionAfter.get("status")).isEqualTo("SUPERSEDED");
        assertThat(versionAfter.get("superseded_reason")).isEqualTo("Jan 26 was actually leave");
        versionBefore.forEach((column, value) -> {
            if (!List.of("status", "superseded_at", "superseded_reason").contains(column)) {
                assertThat(versionAfter.get(column)).as(column).isEqualTo(value);
            }
        });
        assertThat(count("SELECT COUNT(*) FROM audit_event")).isGreaterThan(auditBefore);
    }

    private SettlementView overpaidJanuary() {
        fx.finalizeAndPrepare(ravi, JAN);
        fx.pay(ravi, "200");
        fx.settle(ravi, JAN);
        settlementService.reopenSettlement(ravi.getId(), JAN, "Jan 26 was leave");
        correctJanuaryToLeave();
        fx.finalizeMonth(ravi, JAN);
        return fx.prepare(ravi, JAN);
    }

    @Test
    void overpaymentNeedsAnExplicitChoice() {
        overpaidJanuary();

        assertThatThrownBy(() -> fx.settle(ravi, JAN)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("overpaid by ₹50.00");
    }

    @Test
    void overpaymentConvertedToAnAdvanceMovesThePaymentMoneyAndIsRecoveredLater() {
        overpaidJanuary();

        SettlementView settled = fx.settle(ravi, JAN, new SettleRequest(15000, null, OverpaymentChoice.TO_ADVANCE, null, "Recover next month"));

        assertThat(settled.version().getSettleTreatment()).isEqualTo(SettleTreatment.OVERPAYMENT_TO_ADVANCE);
        assertThat(settled.outstandingPaise()).isZero();
        assertThat(settlementService.advances(ravi.getId())).singleElement().satisfies(b -> {
            assertThat(b.advance().getSource()).isEqualTo(AdvanceSource.OVERPAYMENT);
            assertThat(b.advance().getAmountPaise()).isEqualTo(5000L);
            assertThat(b.advance().getGivenOn()).as("dated when the money was paid").isEqualTo(FinanceFixture.PAID_ON);
        });
        assertThat(count("SELECT COUNT(*) FROM allocation_reversal x JOIN payment_allocation a ON a.id = x.allocation_id "
                + "JOIN payment p ON p.id = a.payment_id WHERE p.worker_id = ?", ravi.getId())).isEqualTo(1L);
        assertThat(rows("SELECT a.kind, a.amount_paise FROM payment_allocation a JOIN payment p ON p.id = a.payment_id WHERE p.worker_id = ? "
                + "AND NOT EXISTS (SELECT 1 FROM allocation_reversal x WHERE x.allocation_id = a.id) ORDER BY a.id", ravi.getId()))
                .extracting(r -> r.get("kind") + ":" + r.get("amount_paise"))
                .containsExactly("KEPT_AFTER_REALLOCATION:15000", "OVERPAYMENT_TO_ADVANCE:5000");
        assertThat(count("SELECT COUNT(*) FROM payment WHERE amount_paise <= 0")).as("no negative payment").isZero();

        SettlementView february = fx.finalizeAndPrepare(ravi, FEB);
        assertThat(february.version().getRecoveryPaise()).isEqualTo(5000L);
    }

    @Test
    void overpaymentCarriedAsANegativeAdjustment() {
        overpaidJanuary();

        fx.settle(ravi, JAN, new SettleRequest(15000, null, OverpaymentChoice.CARRY, FEB, "Deduct next month"));

        assertThat(rows("SELECT kind, amount_paise, money_owed_by_worker FROM financial_adjustment WHERE worker_id = ?", ravi.getId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.get("kind")).isEqualTo("OVERPAYMENT_CARRY");
                    assertThat(r.get("amount_paise")).isEqualTo(-5000);
                    assertThat(r.get("money_owed_by_worker")).isEqualTo(1);
                });
        assertThat(settlementService.view(ravi.getId(), JAN).outstandingPaise()).isZero();
        assertThat(fx.finalizeAndPrepare(ravi, FEB).version().getNetPayablePaise()).isEqualTo(15000L);
    }

    @Test
    void overpaymentCanBeLeftPendingWithAWarning() {
        overpaidJanuary();

        SettlementView settled = fx.settle(ravi, JAN, new SettleRequest(15000, null, OverpaymentChoice.PENDING, null, "Discuss with Ravi"));

        assertThat(settled.version().getSettleTreatment()).isEqualTo(SettleTreatment.OVERPAYMENT_PENDING);
        assertThat(settled.warnings()).anyMatch(w -> w.contains("overpayment of ₹50.00 is pending"));
        assertThat(settlementService.advances(ravi.getId())).as("never converted automatically").isEmpty();
    }

    @Test
    void anOverpaymentAdvanceIsNotRecoveredFromItsOwnMonthAfterReopening() {
        fx.finalizeAndPrepare(ravi, JAN);
        fx.pay(ravi, "150");
        fx.pay(ravi, "50");
        fx.settle(ravi, JAN);
        settlementService.reopenSettlement(ravi.getId(), JAN, "Jan 26 was leave");
        correctJanuaryToLeave();
        fx.finalizeMonth(ravi, JAN);
        fx.prepare(ravi, JAN);
        // The ₹50 overpayment moves the whole second payment into the advance, so that payment no longer pays January.
        fx.settle(ravi, JAN, new SettleRequest(15000, null, OverpaymentChoice.TO_ADVANCE, null, "Recover later"));

        settlementService.reopenSettlement(ravi.getId(), JAN, "Check again");
        fx.finalizeMonth(ravi, JAN);
        SettlementView third = fx.prepare(ravi, JAN);

        assertThat(third.version().getVersionNo()).isEqualTo(3);
        assertThat(third.version().getRecoveryPaise()).as("an overpayment of January is never recovered from January").isZero();
        assertThat(fx.finalizeAndPrepare(ravi, FEB).version().getRecoveryPaise()).isEqualTo(5000L);
    }

    @Test
    void anOverpaymentAdvanceIsDatedAtTheLatestPaymentMovedIntoIt() {
        fx.finalizeAndPrepare(ravi, JAN);
        payOn(LocalDate.of(2026, 1, 20), "100");
        payOn(LocalDate.of(2026, 1, 10), "100");
        fx.settle(ravi, JAN);
        settlementService.reopenSettlement(ravi.getId(), JAN, "Three Mondays were leave");
        leaveOn(12, 19, 26);
        fx.finalizeMonth(ravi, JAN);
        assertThat(fx.prepare(ravi, JAN).overpaymentPaise()).isEqualTo(15000L);

        fx.settle(ravi, JAN, new SettleRequest(5000, null, OverpaymentChoice.TO_ADVANCE, null, "Recover later"));

        // Newest allocation first: all of the 10 January payment and ₹50 of the 20 January payment move.
        assertThat(settlementService.advances(ravi.getId())).singleElement().satisfies(b -> {
            assertThat(b.advance().getAmountPaise()).isEqualTo(15000L);
            assertThat(b.advance().getGivenOn()).isEqualTo(LocalDate.of(2026, 1, 20));
        });
    }

    @Test
    void aPendingOverpaymentNeedsAFreshDecisionAfterReopening() {
        overpaidJanuary();
        fx.settle(ravi, JAN, new SettleRequest(15000, null, OverpaymentChoice.PENDING, null, "Decide later"));

        settlementService.reopenSettlement(ravi.getId(), JAN, "Decide now");
        fx.finalizeMonth(ravi, JAN);
        SettlementView reopened = fx.prepare(ravi, JAN);

        assertThat(reopened.version().getSettleTreatment()).isEqualTo(SettleTreatment.NONE);
        assertThatThrownBy(() -> fx.settle(ravi, JAN)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("overpaid by ₹50.00");
        SettlementView decided = fx.settle(ravi, JAN, new SettleRequest(15000, null, OverpaymentChoice.CARRY, FEB, "Deduct in February"));
        assertThat(decided.version().getSettleTreatment()).isEqualTo(SettleTreatment.OVERPAYMENT_CARRY);
    }

    // --- U2: overpayment caused by a carry-forward ---

    @Test
    void mixedOverpaymentCorrectsTheCarryFirstThenOffersD9ForThePaidPart() {
        fx.finalizeAndPrepare(ravi, JAN);
        fx.pay(ravi, "100");
        fx.settle(ravi, JAN, new SettleRequest(20000, OutstandingChoice.CARRY_FORWARD, null, FEB, "Rest with February"));
        fx.finalizeAndPrepare(ravi, FEB);
        fx.pay(ravi, "300");
        fx.settle(ravi, FEB);
        settlementService.reopenSettlement(ravi.getId(), JAN, "Three Mondays were leave");
        leaveOn(12, 19, 26);
        fx.finalizeMonth(ravi, JAN);
        assertThat(fx.prepare(ravi, JAN).overpaymentPaise()).as("₹100 paid + ₹100 carried against ₹50 earned").isEqualTo(15000L);

        assertThatThrownBy(() -> fx.settle(ravi, JAN, new SettleRequest(5000, null, OverpaymentChoice.TO_ADVANCE, null, "x")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("₹100.00 of the overpayment comes from an earlier carry-forward");

        SettlementView corrected = settlementService.correctCarry(ravi.getId(), JAN, MAR, "Carry was too large");
        assertThat(rows("SELECT amount_paise FROM financial_adjustment WHERE kind = 'CARRY_CORRECTION' AND worker_id = ?", ravi.getId()))
                .extracting(r -> r.get("amount_paise")).containsExactly(-10000);
        assertThat(corrected.overpaymentPaise()).as("only the paid part is left").isEqualTo(5000L);
        assertThatThrownBy(() -> settlementService.correctCarry(ravi.getId(), JAN, MAR, "Again"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("No part of 2026-01's balance comes from an earlier carry-forward");

        SettlementView settled = fx.settle(ravi, JAN, new SettleRequest(5000, null, OverpaymentChoice.TO_ADVANCE, null, "Recover later"));

        assertThat(settled.version().getSettleTreatment()).isEqualTo(SettleTreatment.OVERPAYMENT_TO_ADVANCE);
        assertThat(settled.outstandingPaise()).isZero();
        assertThat(settlementService.advances(ravi.getId())).as("only the paid ₹50 becomes an advance").singleElement()
                .satisfies(b -> assertThat(b.advance().getAmountPaise()).isEqualTo(5000L));
    }

    @Test
    void carryBackedOverpaymentIsCorrectedByReversingTheCarry() {
        fx.finalizeAndPrepare(ravi, JAN);
        fx.settle(ravi, JAN, new SettleRequest(20000, OutstandingChoice.CARRY_FORWARD, null, FEB, "Pay with February"));
        settlementService.reopenSettlement(ravi.getId(), JAN, "Jan 26 was leave");
        correctJanuaryToLeave();
        fx.finalizeMonth(ravi, JAN);
        assertThat(fx.prepare(ravi, JAN).overpaymentPaise()).isEqualTo(5000L);

        assertThatThrownBy(() -> fx.settle(ravi, JAN, new SettleRequest(15000, null, OverpaymentChoice.TO_ADVANCE, null, "x")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("comes from an earlier carry-forward");

        SettlementView corrected = settlementService.correctCarry(ravi.getId(), JAN, null, "Carry was too large");
        assertThat(corrected.outstandingPaise()).isZero();
        assertThat(settlementService.advances(ravi.getId())).as("never an advance").isEmpty();
        assertThat(fx.settle(ravi, JAN).version().getSettleTreatment()).isEqualTo(SettleTreatment.NONE);
        assertThat(fx.finalizeAndPrepare(ravi, FEB).version().getPositiveAdjustmentsPaise()).isEqualTo(15000L);
    }

    @Test
    void carryIntoASettledMonthIsCorrectedInALaterMonth() {
        fx.finalizeAndPrepare(ravi, JAN);
        fx.settle(ravi, JAN, new SettleRequest(20000, OutstandingChoice.CARRY_FORWARD, null, FEB, "Pay with February"));
        fx.finalizeAndPrepare(ravi, FEB);
        fx.pay(ravi, "400");
        fx.settle(ravi, FEB);
        settlementService.reopenSettlement(ravi.getId(), JAN, "Jan 26 was leave");
        correctJanuaryToLeave();
        fx.finalizeMonth(ravi, JAN);
        fx.prepare(ravi, JAN);

        assertThatThrownBy(() -> settlementService.correctCarry(ravi.getId(), JAN, null, "Too large"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already settled. Choose a later month");
        settlementService.correctCarry(ravi.getId(), JAN, MAR, "Too large");

        assertThat(rows("SELECT kind, amount_paise, money_owed_by_worker, target_period_start FROM financial_adjustment "
                + "WHERE kind = 'CARRY_CORRECTION' AND worker_id = ?", ravi.getId())).singleElement().satisfies(r -> {
                    assertThat(r.get("amount_paise")).isEqualTo(-5000);
                    assertThat(r.get("money_owed_by_worker")).isEqualTo(0);
                    assertThat(r.get("target_period_start")).isEqualTo("2026-03-01");
                });
        assertThat(settlementService.view(ravi.getId(), JAN).outstandingPaise()).isZero();
    }

    // --- repeated reopening and recoveries ---

    @Test
    void repeatedReopeningBuildsACleanChain() {
        fx.finalizeAndPrepare(ravi, JAN);
        fx.pay(ravi, "200");
        fx.settle(ravi, JAN);
        for (int i = 0; i < 2; i++) {
            settlementService.reopenSettlement(ravi.getId(), JAN, "Review " + i);
            fx.finalizeMonth(ravi, JAN);
            fx.prepare(ravi, JAN);
            fx.settle(ravi, JAN);
        }

        List<EarningRun> runs = runRepository.findByWorkerIdAndPeriodStartOrderByIdAsc(ravi.getId(), JAN.atDay(1));
        assertThat(runs).hasSize(3);
        assertThat(count("SELECT COUNT(*) FROM earning_run_supersession x JOIN earning_run r ON r.id = x.superseded_run_id "
                + "WHERE x.replacement_run_id IS NOT NULL AND r.worker_id = ?", ravi.getId())).isEqualTo(2L);
        assertThat(runRepository.findActive(ravi.getId(), JAN.atDay(1)).orElseThrow().getId()).isEqualTo(runs.getLast().getId());
        assertThat(rows("SELECT v.version_no, v.status FROM settlement_version v JOIN settlement s ON s.id = v.settlement_id "
                + "WHERE s.worker_id = ? ORDER BY v.version_no", ravi.getId()))
                .extracting(r -> r.get("version_no") + ":" + r.get("status"))
                .containsExactly("1:SUPERSEDED", "2:SUPERSEDED", "3:SETTLED");
        assertThat(count("SELECT COUNT(*) FROM payment_allocation a JOIN payment p ON p.id = a.payment_id WHERE p.worker_id = ?", ravi.getId()))
                .as("the payment is never duplicated").isEqualTo(1L);
    }

    @Test
    void excessRecoveryIsReversedWhenAReopenedMonthEarnsLess() {
        var kept = paymentService.record(new com.homeexpense.dto.FinanceDtos.RecordPayment(ravi.getId(), FinanceFixture.PAID_ON, "300",
                PaymentMethod.CASH, null, null, com.homeexpense.model.finance.AllocationMode.KEEP_UNALLOCATED, null));
        paymentService.recordExcessAsAdvance(kept.payment().getId(), null, "Advance");
        fx.finalizeAndPrepare(ravi, JAN);
        fx.settle(ravi, JAN);
        assertThat(count("SELECT SUM(r.amount_paise) FROM advance_recovery r JOIN advance a ON a.id = r.advance_id WHERE a.worker_id = ?",
                ravi.getId())).isEqualTo(20000L);

        settlementService.reopenSettlement(ravi.getId(), JAN, "Jan 26 was leave");
        correctJanuaryToLeave();
        fx.finalizeMonth(ravi, JAN);
        assertThat(fx.prepare(ravi, JAN).version().getRecoveryPaise()).isEqualTo(15000L);
        fx.settle(ravi, JAN);

        assertThat(count("SELECT COUNT(*) FROM advance_recovery r JOIN advance a ON a.id = r.advance_id WHERE a.worker_id = ?", ravi.getId()))
                .as("old recovery kept").isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM recovery_reversal x JOIN advance_recovery r ON r.id = x.recovery_id JOIN advance a ON a.id = r.advance_id "
                + "WHERE a.worker_id = ?", ravi.getId())).isEqualTo(1L);
        assertThat(settlementService.advances(ravi.getId())).singleElement().satisfies(b -> assertThat(b.balancePaise()).isEqualTo(15000L));
    }
}
