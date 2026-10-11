package com.homeexpense.service;

import static com.homeexpense.service.FinanceFixture.FEB;
import static com.homeexpense.service.FinanceFixture.JAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.util.concurrent.atomic.AtomicInteger;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.FinanceDtos.OutstandingChoice;
import com.homeexpense.dto.FinanceDtos.OverpaymentChoice;
import com.homeexpense.dto.FinanceDtos.PaymentResult;
import com.homeexpense.dto.FinanceDtos.ReplacementPreview;
import com.homeexpense.dto.FinanceDtos.ReplacementRequest;
import com.homeexpense.dto.FinanceDtos.SettleRequest;
import com.homeexpense.dto.WorkDetails;
import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;
import com.homeexpense.model.finance.PaymentMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Not @Transactional: each service call commits, as in the running application. A failure is
 * injected at the final audit step of each multi-step operation, after its financial rows have
 * been written, to prove the whole operation rolls back.
 */
class FinanceAtomicityIntegrationTest extends SqliteIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @MockitoBean
    private AuditService auditService;

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
    private Worker worker;

    @BeforeEach
    void setUp() {
        int n = SEQUENCE.incrementAndGet();
        EarningsFixture earnings = new EarningsFixture(workerService, catalogService, scheduleService, payService, dailyWorkService,
                earningsService);
        fx = new FinanceFixture(earnings, settlementService, paymentService, "Atomicity mopping " + n);
        worker = fx.worker("Atomicity worker " + n, "50");
    }

    @AfterEach
    void clearInjectedFailures() {
        reset(auditService);
    }

    private void failOn(String entityType, String action) {
        doThrow(new IllegalStateException("Injected failure at " + action))
                .when(auditService).record(anyLong(), eq(entityType), any(), eq(action), anyString());
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, worker.getId());
    }

    @Test
    void failedPaymentRecordingLeavesNoPaymentOrAllocation() {
        fx.finalizeAndPrepare(worker, JAN);
        failOn(PaymentService.ENTITY_PAYMENT, "RECORD");

        assertThatThrownBy(() -> fx.pay(worker, "150")).hasMessageContaining("Injected failure");

        assertThat(count("SELECT COUNT(*) FROM payment WHERE worker_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment_allocation a JOIN settlement s ON s.id = a.settlement_id WHERE s.worker_id = ?")).isZero();
    }

    @Test
    void failedSettleLeavesTheVersionOpenWithoutRecoveriesOrCarries() {
        PaymentResult kept = fx.payKeepUnallocated(worker, "50");
        paymentService.recordExcessAsAdvance(kept.payment().getId(), null, "Advance");
        fx.finalizeAndPrepare(worker, JAN);
        failOn(SettlementService.ENTITY_VERSION, "SETTLE");

        assertThatThrownBy(() -> fx.settle(worker, JAN, new SettleRequest(15000, OutstandingChoice.CARRY_FORWARD, null, FEB, "Next month")))
                .hasMessageContaining("Injected failure");

        assertThat(count("SELECT COUNT(*) FROM settlement_version v JOIN settlement s ON s.id = v.settlement_id WHERE s.worker_id = ? "
                + "AND v.status = 'OPEN'")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM advance_recovery r JOIN advance a ON a.id = r.advance_id WHERE a.worker_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM financial_adjustment WHERE worker_id = ?")).isZero();
    }

    @Test
    void failedReplacementLeavesTheOriginalPaymentActive() {
        fx.finalizeAndPrepare(worker, JAN);
        PaymentResult paid = fx.pay(worker, "150");
        ReplacementRequest request = new ReplacementRequest(FinanceFixture.PAID_ON, "200", PaymentMethod.CASH, null, null, null, null);
        ReplacementPreview preview = paymentService.previewReplacement(paid.payment().getId(), request);
        failOn(PaymentService.ENTITY_PAYMENT, "RECORD_REPLACEMENT");

        assertThatThrownBy(() -> paymentService.confirmReplacement(paid.payment().getId(), request, preview, "Was ₹200"))
                .hasMessageContaining("Injected failure");

        assertThat(count("SELECT COUNT(*) FROM payment WHERE worker_id = ?")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM payment_void v JOIN payment p ON p.id = v.payment_id WHERE p.worker_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM allocation_reversal x JOIN payment_allocation a ON a.id = x.allocation_id "
                + "JOIN payment p ON p.id = a.payment_id WHERE p.worker_id = ?")).isZero();
    }

    @Test
    void failedReopeningLeavesTheSettlementAndEarningsInForce() {
        fx.finalizeAndPrepare(worker, JAN);
        fx.pay(worker, "200");
        fx.settle(worker, JAN);
        failOn(EarningsService.ENTITY_RUN, "REOPEN");

        assertThatThrownBy(() -> settlementService.reopenSettlement(worker.getId(), JAN, "Check")).hasMessageContaining("Injected failure");

        assertThat(count("SELECT COUNT(*) FROM settlement_version v JOIN settlement s ON s.id = v.settlement_id WHERE s.worker_id = ? "
                + "AND v.status = 'SETTLED'")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM earning_run_supersession x JOIN earning_run r ON r.id = x.superseded_run_id "
                + "WHERE r.worker_id = ?")).isZero();
    }

    @Test
    void failedOverpaymentConversionLeavesAllocationsUntouched() {
        fx.finalizeAndPrepare(worker, JAN);
        fx.pay(worker, "200");
        fx.settle(worker, JAN);
        settlementService.reopenSettlement(worker.getId(), JAN, "Jan 26 was leave");
        WorkRecord record = dailyWorkService.history(worker.getId(), java.time.LocalDate.of(2026, 1, 26), java.time.LocalDate.of(2026, 1, 26))
                .stream().filter(WorkRecord::isCurrent).findFirst().orElseThrow();
        dailyWorkService.correct(record.getId(), WorkDetails.notDone(AbsenceReason.APPROVED_LEAVE), "Leave", false);
        fx.finalizeMonth(worker, JAN);
        fx.prepare(worker, JAN);
        failOn(SettlementService.ENTITY_ADVANCE, "CREATE_FROM_OVERPAYMENT");

        assertThatThrownBy(() -> fx.settle(worker, JAN, new SettleRequest(15000, null, OverpaymentChoice.TO_ADVANCE, null, "Recover")))
                .hasMessageContaining("Injected failure");

        assertThat(count("SELECT COUNT(*) FROM advance WHERE worker_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM allocation_reversal x JOIN payment_allocation a ON a.id = x.allocation_id "
                + "JOIN payment p ON p.id = a.payment_id WHERE p.worker_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM settlement_version v JOIN settlement s ON s.id = v.settlement_id WHERE s.worker_id = ? "
                + "AND v.status = 'OPEN'")).isEqualTo(1L);
    }
}
