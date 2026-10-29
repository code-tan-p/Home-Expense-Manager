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
import com.homeexpense.dto.FinanceDtos.PaymentResult;
import com.homeexpense.dto.FinanceDtos.SettleRequest;
import com.homeexpense.dto.FinanceDtos.SettlementView;
import com.homeexpense.dto.FinanceDtos.ShortfallChoice;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.Worker;
import com.homeexpense.model.finance.Advance;
import com.homeexpense.model.finance.AdvanceSource;
import com.homeexpense.model.finance.PaymentAllocation;
import com.homeexpense.model.finance.SettleTreatment;
import com.homeexpense.model.finance.SettlementItemType;
import com.homeexpense.model.finance.SettlementRounding;
import com.homeexpense.model.finance.SettlementStatus;
import com.homeexpense.model.finance.ShortfallTreatment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Payments, allocation, advances, carry-forward, rounding and shortfalls (₹50 a Monday; January earns ₹200). */
@Transactional
class SettlementFlowIntegrationTest extends SqliteIntegrationTest {

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

    private static Map<String, Long> monthsOf(PaymentResult result) {
        return result.allocations().stream().collect(java.util.stream.Collectors.toMap(
                a -> java.time.YearMonth.from(a.getSettlement().getPeriodStart()).toString(), PaymentAllocation::getAmountPaise));
    }

    private Advance advanceFromKeptPayment(String amount) {
        PaymentResult kept = fx.payKeepUnallocated(ravi, amount);
        return paymentService.recordExcessAsAdvance(kept.payment().getId(), null, "Asked for an advance");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // --- preparing ---

    @Test
    void settlementNeedsFinalizedEarnings() {
        assertThatThrownBy(() -> fx.prepare(ravi, JAN)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("Finalize");
    }

    @Test
    void preparedSettlementShowsEarningsAndIsReusedWhileOpen() {
        SettlementView view = fx.finalizeAndPrepare(ravi, JAN);

        assertThat(view.version().getStatus()).isEqualTo(SettlementStatus.OPEN);
        assertThat(view.version().getVersionNo()).isEqualTo(1);
        assertThat(view.version().getEarningsPaise()).isEqualTo(20000L);
        assertThat(view.version().getNetPayablePaise()).isEqualTo(20000L);
        assertThat(view.outstandingPaise()).isEqualTo(20000L);
        assertThat(view.items()).extracting(i -> i.getItemType()).containsExactly(SettlementItemType.EARNINGS);
        assertThat(fx.prepare(ravi, JAN).version().getId()).isEqualTo(view.version().getId());
    }

    // --- allocation (Q1, Q3, Q4) ---

    @Test
    void autoPaymentsGoToTheOldestOutstandingMonthsFirst() {
        fx.finalizeAndPrepare(ravi, JAN);
        fx.finalizeAndPrepare(ravi, FEB);
        fx.finalizeAndPrepare(ravi, MAR);

        PaymentResult first = fx.pay(ravi, "500");
        assertThat(monthsOf(first)).containsExactlyInAnyOrderEntriesOf(Map.of("2026-01", 20000L, "2026-02", 20000L, "2026-03", 10000L));
        assertThat(first.unallocatedPaise()).isZero();

        PaymentResult second = fx.pay(ravi, "300");
        assertThat(monthsOf(second)).containsExactly(Map.entry("2026-03", 15000L));
        assertThat(second.unallocatedPaise()).isEqualTo(15000L);
        assertThat(second.warnings()).singleElement().asString().contains("unallocated").contains("advance").contains("credit");
        assertThat(fx.settlementService.view(ravi.getId(), MAR).outstandingPaise()).isZero();
    }

    @Test
    void explicitTargetsAreHonouredAndCapped() {
        fx.finalizeAndPrepare(ravi, JAN);
        fx.finalizeAndPrepare(ravi, FEB);

        PaymentResult result = fx.payExplicit(ravi, "100", FEB, "100");

        assertThat(monthsOf(result)).containsExactly(Map.entry("2026-02", 10000L));
        assertThat(settlementService.view(ravi.getId(), JAN).outstandingPaise()).isEqualTo(20000L);
        assertThatThrownBy(() -> fx.payExplicit(ravi, "500", JAN, "300"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("only ₹200.00 is outstanding");
        assertThatThrownBy(() -> fx.payExplicit(ravi, "50", MAR, "50"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("no settlement for that month yet");
        assertThat(count("SELECT COUNT(*) FROM payment WHERE worker_id = ?", ravi.getId())).as("rejected payments are not saved").isEqualTo(1L);
    }

    // --- early payments (D4, Q2, R1) ---

    @Test
    void earlyPaymentsStayUnallocatedUntilAllocatedExplicitly() {
        PaymentResult early = fx.pay(ravi, "150");
        assertThat(early.allocations()).as("nothing outstanding yet").isEmpty();
        assertThat(early.unallocatedPaise()).isEqualTo(15000L);

        SettlementView prepared = fx.finalizeAndPrepare(ravi, JAN);
        assertThat(prepared.allocatedPaise()).as("preparing never allocates credit").isZero();
        assertThat(prepared.unallocatedCreditPaise()).isEqualTo(15000L);
        assertThat(prepared.warnings()).anyMatch(w -> w.contains("unallocated credit"));

        PaymentResult kept = fx.payKeepUnallocated(ravi, "50");
        assertThat(kept.allocations()).as("explicitly kept unallocated even though January is outstanding").isEmpty();

        paymentService.allocate(early.payment().getId(), JAN, null);
        assertThat(settlementService.view(ravi.getId(), JAN).outstandingPaise()).isEqualTo(5000L);
    }

    // --- excess and advances (D5, D2, Q8, R3) ---

    @Test
    void excessRecordedAsAnAdvanceIsRecoveredFromALaterMonthWhenSettled() {
        fx.finalizeAndPrepare(ravi, JAN);
        PaymentResult payment = fx.pay(ravi, "300");
        Advance advance = paymentService.recordExcessAsAdvance(payment.payment().getId(), null, "Extra for a family function");
        assertThat(advance.getAmountPaise()).isEqualTo(10000L);
        assertThat(advance.getSource()).isEqualTo(AdvanceSource.PAYMENT_EXCESS);

        assertThat(fx.net(ravi, JAN)).as("the payment that funded the advance paid January, so January does not recover it").isEqualTo(20000L);
        fx.settle(ravi, JAN);

        SettlementView february = fx.finalizeAndPrepare(ravi, FEB);
        assertThat(february.version().getRecoveryPaise()).isEqualTo(10000L);
        assertThat(february.version().getNetPayablePaise()).isEqualTo(10000L);
        assertThat(count("SELECT COUNT(*) FROM advance_recovery WHERE advance_id = ?", advance.getId())).as("not until settled (Q8)").isZero();

        fx.pay(ravi, "100");
        fx.settle(ravi, FEB);
        assertThat(count("SELECT COUNT(*) FROM advance_recovery WHERE advance_id = ?", advance.getId())).isEqualTo(1L);
        assertThat(settlementService.advances(ravi.getId())).singleElement().satisfies(b -> assertThat(b.balancePaise()).isZero());
    }

    @Test
    void recoveryTakesOldestAdvancesFirstAndNeverMoreThanEarnings() {
        Advance first = advanceFromKeptPayment("150");
        Advance second = advanceFromKeptPayment("250");

        SettlementView january = fx.finalizeAndPrepare(ravi, JAN);
        assertThat(january.items()).filteredOn(i -> i.getItemType() == SettlementItemType.ADVANCE_RECOVERY)
                .extracting(i -> i.getAdvance().getId(), i -> i.getAmountPaise())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(first.getId(), -15000L),
                        org.assertj.core.groups.Tuple.tuple(second.getId(), -5000L));
        assertThat(january.version().getNetPayablePaise()).isZero();
        fx.settle(ravi, JAN);

        SettlementView february = fx.finalizeAndPrepare(ravi, FEB);
        assertThat(february.version().getRecoveryPaise()).as("the rest of the second advance").isEqualTo(20000L);
        fx.settle(ravi, FEB);
        assertThat(settlementService.advances(ravi.getId())).allSatisfy(b -> assertThat(b.balancePaise()).isZero());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO advance_recovery (advance_id, settlement_id, settlement_version_id, amount_paise, "
                + "recovered_at) SELECT ?, settlement_id, id, 100, 'x' FROM settlement_version WHERE status = 'SETTLED' LIMIT 1", second.getId()))
                .hasMessageContaining("cannot exceed the advance");
    }

    @Test
    void anAdvanceGivenAfterTheMonthEndsIsRecoveredOnlyFromLaterMonths() {
        PaymentResult kept = paymentService.record(new com.homeexpense.dto.FinanceDtos.RecordPayment(ravi.getId(),
                LocalDate.of(2026, 2, 10), "50", com.homeexpense.model.finance.PaymentMethod.CASH, null, null,
                com.homeexpense.model.finance.AllocationMode.KEEP_UNALLOCATED, null));
        Advance advance = paymentService.recordExcessAsAdvance(kept.payment().getId(), null, "February advance");
        assertThat(advance.getGivenOn()).isEqualTo(LocalDate.of(2026, 2, 10));

        assertThat(fx.finalizeAndPrepare(ravi, JAN).version().getRecoveryPaise()).as("given after January ended").isZero();
        assertThat(fx.finalizeAndPrepare(ravi, FEB).version().getRecoveryPaise()).isEqualTo(5000L);
    }

    @Test
    void aPositiveCarryDoesNotRaiseTheRecoveryCap() {
        fx.finalizeAndPrepare(ravi, JAN);
        fx.settle(ravi, JAN, new SettleRequest(20000, OutstandingChoice.CARRY_FORWARD, null, FEB, "Paying January in February"));
        advanceFromKeptPayment("300");

        SettlementView february = fx.finalizeAndPrepare(ravi, FEB);

        assertThat(february.version().getPositiveAdjustmentsPaise()).isEqualTo(20000L);
        assertThat(february.version().getRecoveryPaise()).as("capped at February's own earnings, not earnings + carry").isEqualTo(20000L);
        assertThat(february.version().getNetPayablePaise()).isEqualTo(20000L);
    }

    @Test
    void aNegativeAdjustmentLowersTheRecoveryCap() {
        settlementService.addCorrection(ravi.getId(), JAN, "-150", false, "Agreed deduction for a broken vase");
        advanceFromKeptPayment("300");

        SettlementView january = fx.finalizeAndPrepare(ravi, JAN);

        assertThat(january.version().getNegativeAdjustmentsPaise()).isEqualTo(-15000L);
        assertThat(january.version().getRecoveryPaise()).isEqualTo(5000L);
        assertThat(january.version().getNetPayablePaise()).isZero();
    }

    // --- outstanding and carry-forward (D6, Q7, R5, R6) ---

    @Test
    void outstandingNeedsAnExplicitChoiceToSettle() {
        fx.finalizeAndPrepare(ravi, JAN);

        assertThatThrownBy(() -> fx.settle(ravi, JAN))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("still unpaid").hasMessageContaining("carry it forward");
        assertThatThrownBy(() -> fx.settle(ravi, JAN, new SettleRequest(20000, OutstandingChoice.CARRY_FORWARD, null, JAN, "x")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("later than 2026-01");
        assertThat(settlementService.view(ravi.getId(), JAN).version().getStatus()).isEqualTo(SettlementStatus.OPEN);
    }

    @Test
    void carryForwardMovesTheBalanceExactlyOnce() {
        fx.finalizeAndPrepare(ravi, JAN);
        SettlementView january = fx.settle(ravi, JAN, new SettleRequest(20000, OutstandingChoice.CARRY_FORWARD, null, null,
                "Paid with February"));

        assertThat(january.version().getSettleTreatment()).isEqualTo(SettleTreatment.CARRY_FORWARD);
        assertThat(january.outstandingPaise()).as("no longer payable in January").isZero();
        assertThat(january.carriedOutPaise()).isEqualTo(20000L);

        fx.finalizeAndPrepare(ravi, FEB);
        SettlementView february = fx.prepare(ravi, FEB);
        assertThat(february.items()).filteredOn(i -> i.getItemType() == SettlementItemType.ADJUSTMENT).hasSize(1);
        assertThat(february.version().getNetPayablePaise()).isEqualTo(40000L);

        PaymentResult payment = fx.pay(ravi, "400");
        assertThat(monthsOf(payment)).as("January is not payable any more").containsExactly(Map.entry("2026-02", 40000L));
    }

    @Test
    void carryIntoASettledMonthIsRejected() {
        fx.finalizeAndPrepare(ravi, FEB);
        fx.pay(ravi, "200");
        fx.settle(ravi, FEB);
        fx.finalizeAndPrepare(ravi, JAN);

        assertThatThrownBy(() -> fx.settle(ravi, JAN, new SettleRequest(20000, OutstandingChoice.CARRY_FORWARD, null, FEB, "x")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("2026-02 is already settled");
        SettlementView january = fx.settle(ravi, JAN, new SettleRequest(20000, OutstandingChoice.CARRY_FORWARD, null, MAR, "Next open month"));
        assertThat(january.version().getStatus()).isEqualTo(SettlementStatus.SETTLED);
    }

    @Test
    void aSettledMonthLeftOutstandingCanStillBePaidWithoutChangingItsSnapshot() {
        fx.finalizeAndPrepare(ravi, JAN);
        SettlementView settled = fx.settle(ravi, JAN, new SettleRequest(20000, OutstandingChoice.LEAVE_OUTSTANDING, null, null, null));
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM settlement_version WHERE id = ?", settled.version().getId());

        PaymentResult payment = fx.pay(ravi, "150");

        assertThat(monthsOf(payment)).containsExactly(Map.entry("2026-01", 15000L));
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_version WHERE id = ?", settled.version().getId())).isEqualTo(before);
        assertThat(settlementService.view(ravi.getId(), JAN).outstandingPaise()).isEqualTo(5000L);
    }

    @Test
    void changedFiguresAreNotSettledUnseen() {
        fx.finalizeAndPrepare(ravi, JAN);
        long reviewed = fx.net(ravi, JAN);
        settlementService.addCorrection(ravi.getId(), JAN, "25", null, "Bonus agreed for the festival");

        assertThatThrownBy(() -> settlementService.settle(ravi.getId(), JAN, new SettleRequest(reviewed, OutstandingChoice.LEAVE_OUTSTANDING,
                null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("changed from ₹200.00 to ₹225.00");
    }

    // --- rounding (D10) ---

    @Test
    void roundingIsASeparateUndoableItem() {
        Worker asha = fx.worker("Asha", "50.15");
        assertThat(fx.finalizeAndPrepare(asha, JAN).version().getNetPayablePaise()).isEqualTo(20060L);

        assertThat(settlementService.setRounding(asha.getId(), JAN, SettlementRounding.UP).version().getNetPayablePaise()).isEqualTo(20100L);
        assertThat(settlementService.setRounding(asha.getId(), JAN, SettlementRounding.DOWN).version().getNetPayablePaise()).isEqualTo(20000L);
        SettlementView nearest = settlementService.setRounding(asha.getId(), JAN, SettlementRounding.NEAREST);
        assertThat(nearest.version().getRoundingPaise()).isEqualTo(40L);
        assertThat(nearest.items()).filteredOn(i -> i.getItemType() == SettlementItemType.ROUNDING).singleElement()
                .satisfies(i -> assertThat(i.getAmountPaise()).isEqualTo(40L));
        assertThat(nearest.version().getEarningsPaise()).as("earnings untouched").isEqualTo(20060L);

        SettlementView exact = settlementService.setRounding(asha.getId(), JAN, SettlementRounding.EXACT);
        assertThat(exact.version().getNetPayablePaise()).isEqualTo(20060L);
        assertThat(exact.items()).noneMatch(i -> i.getItemType() == SettlementItemType.ROUNDING);

        fx.finalizeAndPrepare(ravi, JAN);
        assertThat(settlementService.setRounding(ravi.getId(), JAN, SettlementRounding.UP).items())
                .as("whole rupees need no rounding item").noneMatch(i -> i.getItemType() == SettlementItemType.ROUNDING);
    }

    @Test
    void roundingArithmetic() {
        assertThat(SettlementService.rounding(20060, SettlementRounding.NEAREST)).isEqualTo(40);
        assertThat(SettlementService.rounding(20049, SettlementRounding.NEAREST)).isEqualTo(-49);
        assertThat(SettlementService.rounding(20050, SettlementRounding.NEAREST)).as("half rounds up").isEqualTo(50);
        assertThat(SettlementService.rounding(20001, SettlementRounding.UP)).isEqualTo(99);
        assertThat(SettlementService.rounding(20099, SettlementRounding.DOWN)).isEqualTo(-99);
        assertThat(SettlementService.rounding(20000, SettlementRounding.UP)).isZero();
        assertThat(SettlementService.rounding(20099, SettlementRounding.EXACT)).isZero();
        assertThat(SettlementService.rounding(99, SettlementRounding.DOWN)).as("never below zero").isEqualTo(-99);
    }

    // --- shortfall (R4, U1) ---

    @Test
    void shortfallBlocksUntilHandledAndOnlyOwedMoneyBecomesAnAdvance() {
        assertThatThrownBy(() -> settlementService.addCorrection(ravi.getId(), JAN, "-300", null, "Paid twice"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("money the worker owes");
        settlementService.addCorrection(ravi.getId(), JAN, "-300", true, "Paid twice by mistake");
        settlementService.addCorrection(ravi.getId(), JAN, "-100", false, "Agreed deduction");

        SettlementView january = fx.finalizeAndPrepare(ravi, JAN);
        assertThat(january.version().getShortfallPaise()).isEqualTo(20000L);
        assertThat(january.version().getNetPayablePaise()).isZero();
        assertThat(january.blockers()).anyMatch(b -> b.contains("more than this month's earnings"));
        assertThatThrownBy(() -> fx.settle(ravi, JAN)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot be settled yet");

        assertThatThrownBy(() -> settlementService.resolveShortfall(ravi.getId(), JAN, ShortfallChoice.TO_ADVANCE, "150", null, "Owed"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Only ₹100.00");
        settlementService.resolveShortfall(ravi.getId(), JAN, ShortfallChoice.TO_ADVANCE, "100", null, "Money paid twice");
        assertThat(settlementService.advances(ravi.getId())).singleElement().satisfies(b -> {
            assertThat(b.advance().getSource()).isEqualTo(AdvanceSource.REMAINDER);
            assertThat(b.advance().getAmountPaise()).isEqualTo(10000L);
        });
        SettlementView handled = settlementService.resolveShortfall(ravi.getId(), JAN, ShortfallChoice.CARRY, "100", FEB, "Rest next month");
        assertThat(handled.blockers()).isEmpty();
        assertThat(count("SELECT money_owed_by_worker FROM financial_adjustment WHERE kind = 'REMAINDER_CARRY' AND worker_id = ?",
                ravi.getId())).as("the owed part was already used for the advance").isZero();

        assertThat(fx.settle(ravi, JAN).version().getStatus()).isEqualTo(SettlementStatus.SETTLED);
        assertThat(fx.finalizeAndPrepare(ravi, FEB).version().getNegativeAdjustmentsPaise()).isEqualTo(-10000L);
    }

    @Test
    void aShortfallCarrySplitsIntoOwedAndNotOwedRows() {
        settlementService.addCorrection(ravi.getId(), JAN, "-300", true, "Paid twice by mistake");
        settlementService.addCorrection(ravi.getId(), JAN, "-100", false, "Agreed deduction");
        fx.finalizeAndPrepare(ravi, JAN);

        settlementService.resolveShortfall(ravi.getId(), JAN, ShortfallChoice.CARRY, "150", FEB, "Next month");

        assertThat(jdbc.queryForList("SELECT amount_paise, money_owed_by_worker FROM financial_adjustment WHERE worker_id = ? "
                + "AND kind = 'REMAINDER_CARRY' ORDER BY id", ravi.getId()))
                .extracting(r -> r.get("amount_paise") + ":" + r.get("money_owed_by_worker"))
                .as("₹200 short, ₹100 of it not owed, so at most ₹100 is owed").containsExactly("-10000:1", "-5000:0");
        assertThat(settlementService.view(ravi.getId(), JAN).blockers()).as("₹50 still needs a decision")
                .anyMatch(b -> b.startsWith("₹50.00 of negative adjustments"));
    }

    @Test
    void aShortfallOfOnlyNotOwedAdjustmentsCannotBecomeAnAdvance() {
        settlementService.addCorrection(ravi.getId(), JAN, "-300", false, "Agreed deduction");
        fx.finalizeAndPrepare(ravi, JAN);

        assertThatThrownBy(() -> settlementService.resolveShortfall(ravi.getId(), JAN, ShortfallChoice.TO_ADVANCE, "50", null, "Owed?"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Only ₹0.00");
        assertThat(settlementService.advances(ravi.getId())).isEmpty();
    }

    @Test
    void shortfallCanBeLeftPendingWithAWarning() {
        settlementService.addCorrection(ravi.getId(), JAN, "-300", false, "Agreed deduction");
        fx.finalizeAndPrepare(ravi, JAN);

        SettlementView pending = settlementService.resolveShortfall(ravi.getId(), JAN, ShortfallChoice.PENDING, null, null, "Decide later");

        assertThat(pending.blockers()).isEmpty();
        assertThat(pending.warnings()).anyMatch(w -> w.contains("left pending"));
        assertThat(fx.settle(ravi, JAN).version().getShortfallTreatment()).isEqualTo(ShortfallTreatment.PENDING);
    }

    // --- informational unpaid time (D11) and deactivation warnings (D13) ---

    @Test
    void approvedUnpaidTimeIsShownButNotDeductedAgain() {
        fx.earnings.dailyWorkService.recordScheduled(ravi.getId(), LocalDate.of(2026, 1, 26), fx.entryId(ravi),
                WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE));

        SettlementView january = fx.finalizeAndPrepare(ravi, JAN);

        assertThat(january.version().getNetPayablePaise()).isEqualTo(15000L);
        assertThat(january.items()).filteredOn(i -> i.getItemType() == SettlementItemType.UNPAID_TIME_INFO).singleElement()
                .satisfies(i -> {
                    assertThat(i.getAmountPaise()).isZero();
                    assertThat(i.getExplanation()).contains("not deducted again").contains("approved leave");
                });
        assertThat(settlementService.summary(ravi.getId(), JAN).unpaidTimeInfo()).hasSize(1);
        assertThat(settlementService.summary(ravi.getId(), JAN).netPayablePaise()).isEqualTo(15000L);
    }

    @Test
    void deactivationWarnsAboutOpenBalancesButIsAllowed() {
        advanceFromKeptPayment("50");
        fx.finalizeAndPrepare(ravi, JAN);
        fx.settle(ravi, JAN, new SettleRequest(15000, OutstandingChoice.LEAVE_OUTSTANDING, null, null, null));
        fx.payKeepUnallocated(ravi, "20");

        List<String> warnings = settlementService.deactivationWarnings(ravi.getId());

        assertThat(warnings).anyMatch(w -> w.contains("2026-01 has ₹150.00 unpaid"))
                .anyMatch(w -> w.contains("₹20.00 of payments is unallocated credit"));
        workerService.deactivate(ravi.getId(), LocalDate.of(2026, 9, 30), "Moved away");
        assertThat(count("SELECT COUNT(*) FROM settlement WHERE worker_id = ?", ravi.getId())).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM payment WHERE worker_id = ?", ravi.getId())).isEqualTo(2L);
    }
}
