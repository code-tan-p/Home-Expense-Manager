package com.homeexpense.service;

import static com.homeexpense.service.FinanceFixture.FEB;
import static com.homeexpense.service.FinanceFixture.JAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.homeexpense.SqliteIntegrationTest;
import com.homeexpense.dto.FinanceDtos.AllocationTarget;
import com.homeexpense.dto.FinanceDtos.PaymentResult;
import com.homeexpense.dto.FinanceDtos.ProposedAllocation;
import com.homeexpense.dto.FinanceDtos.ReplacementPreview;
import com.homeexpense.dto.FinanceDtos.ReplacementRequest;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.Worker;
import com.homeexpense.model.finance.AllocationMode;
import com.homeexpense.model.finance.PaymentMethod;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** D7 and R2: void-and-replace with a reviewed preview; the original payment is always kept. */
@Transactional
class PaymentReplacementIntegrationTest extends SqliteIntegrationTest {

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
        fx.finalizeAndPrepare(ravi, JAN);
        fx.finalizeAndPrepare(ravi, FEB);
    }

    private static ReplacementRequest replace(String amount, AllocationMode mode, List<AllocationTarget> targets) {
        return new ReplacementRequest(FinanceFixture.PAID_ON, amount, PaymentMethod.CASH, null, "corrected", mode, targets);
    }

    /** Rows of a payment-related table that belong to this test's worker. */
    private long count(String table) {
        entityManager.flush();
        String sql = switch (table) {
            case "payment" -> "SELECT COUNT(*) FROM payment WHERE worker_id = ?";
            case "payment_allocation" -> "SELECT COUNT(*) FROM payment_allocation a JOIN payment p ON p.id = a.payment_id WHERE p.worker_id = ?";
            case "allocation_reversal" -> "SELECT COUNT(*) FROM allocation_reversal x JOIN payment_allocation a ON a.id = x.allocation_id "
                    + "JOIN payment p ON p.id = a.payment_id WHERE p.worker_id = ?";
            case "payment_void" -> "SELECT COUNT(*) FROM payment_void v JOIN payment p ON p.id = v.payment_id WHERE p.worker_id = ?";
            case "audit_event" -> "SELECT COUNT(*) FROM audit_event WHERE worker_id = ?";
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.queryForObject(sql, Long.class, ravi.getId());
    }

    @Test
    void previewWritesNothing() {
        PaymentResult paid = fx.pay(ravi, "250");
        Map<String, Long> before = Map.of("payment", count("payment"), "payment_allocation", count("payment_allocation"),
                "allocation_reversal", count("allocation_reversal"), "payment_void", count("payment_void"), "audit_event", count("audit_event"));

        paymentService.previewReplacement(paid.payment().getId(), replace("300", null, null));

        before.forEach((table, rows) -> assertThat(count(table)).as(table).isEqualTo(rows));
    }

    @Test
    void autoReplacementIsRecalculatedAndTheOriginalIsKept() {
        PaymentResult paid = fx.pay(ravi, "250");
        Map<String, Object> originalRow = jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", paid.payment().getId());

        ReplacementPreview preview = paymentService.previewReplacement(paid.payment().getId(), replace("300", null, null));
        assertThat(preview.mode()).isEqualTo(AllocationMode.AUTO);
        assertThat(preview.allocations()).containsExactly(new ProposedAllocation(JAN, 20000, true, null),
                new ProposedAllocation(FEB, 10000, true, null));

        PaymentResult replaced = paymentService.confirmReplacement(paid.payment().getId(), replace("300", null, null), preview,
                "Actually paid ₹300");

        assertThat(replaced.payment().getReplaces().getId()).isEqualTo(paid.payment().getId());
        assertThat(jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", paid.payment().getId())).as("original row unchanged").isEqualTo(originalRow);
        assertThat(jdbc.queryForMap("SELECT reason, replacement_payment_id FROM payment_void WHERE payment_id = ?", paid.payment().getId()))
                .containsEntry("reason", "Actually paid ₹300").containsEntry("replacement_payment_id", replaced.payment().getId().intValue());
        assertThat(count("allocation_reversal")).isEqualTo(2L);
        assertThat(settlementService.view(ravi.getId(), JAN).outstandingPaise()).isZero();
        assertThat(settlementService.view(ravi.getId(), FEB).outstandingPaise()).isEqualTo(10000L);
        assertThatThrownBy(() -> paymentService.allocate(paid.payment().getId(), FEB, "10"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("voided");
    }

    @Test
    void invalidExplicitTargetsAreNeverRedirected() {
        PaymentResult paid = fx.payExplicit(ravi, "150", FEB, "150");

        ReplacementPreview preview = paymentService.previewReplacement(paid.payment().getId(), replace("100", null, null));
        assertThat(preview.allocations()).singleElement().satisfies(p -> {
            assertThat(p.month()).isEqualTo(FEB);
            assertThat(p.valid()).isFalse();
            assertThat(p.problem()).contains("more than the replacement amount");
        });
        assertThatThrownBy(() -> paymentService.confirmReplacement(paid.payment().getId(), replace("100", null, null), preview, "Typo"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("never redirected");
        assertThat(count("payment_void")).isZero();

        ReplacementRequest chosen = replace("100", AllocationMode.EXPLICIT, List.of(new AllocationTarget(FEB, "100")));
        ReplacementPreview fixed = paymentService.previewReplacement(paid.payment().getId(), chosen);
        PaymentResult replaced = paymentService.confirmReplacement(paid.payment().getId(), chosen, fixed, "Typo");
        assertThat(replaced.allocations()).singleElement().satisfies(a -> assertThat(a.getAmountPaise()).isEqualTo(10000L));
        assertThat(settlementService.view(ravi.getId(), JAN).outstandingPaise()).as("not moved to January").isEqualTo(20000L);
    }

    @Test
    void keepUnallocatedReplacementStaysUnallocated() {
        PaymentResult kept = fx.payKeepUnallocated(ravi, "50");

        ReplacementPreview preview = paymentService.previewReplacement(kept.payment().getId(), replace("60", null, null));
        PaymentResult replaced = paymentService.confirmReplacement(kept.payment().getId(), replace("60", null, null), preview, "Was ₹60");

        assertThat(preview.allocations()).isEmpty();
        assertThat(replaced.allocations()).isEmpty();
        assertThat(replaced.unallocatedPaise()).isEqualTo(6000L);
    }

    @Test
    void aChangedPreviewMustBeReviewedAgain() {
        PaymentResult paid = fx.pay(ravi, "100");
        ReplacementPreview preview = paymentService.previewReplacement(paid.payment().getId(), replace("200", null, null));
        fx.pay(ravi, "100");

        assertThatThrownBy(() -> paymentService.confirmReplacement(paid.payment().getId(), replace("200", null, null), preview, "Was ₹200"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Review it again");
        assertThat(count("payment_void")).isZero();
    }

    @Test
    void paymentsForASettledMonthAreCorrectedByAdjustmentOnly() {
        PaymentResult paid = fx.pay(ravi, "200");
        fx.settle(ravi, JAN);

        assertThatThrownBy(() -> paymentService.previewReplacement(paid.payment().getId(), replace("150", null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("settled. Correct it with an adjustment");
        assertThatThrownBy(() -> paymentService.confirmReplacement(paid.payment().getId(), replace("150", null, null), null, "x"))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(count("payment_void")).isZero();
    }
}
