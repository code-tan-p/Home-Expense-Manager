package com.homeexpense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/** V9 constraints and triggers, proved with raw SQL that bypasses the services. */
@Transactional
class FinanceSchemaIntegrationTest extends SqliteIntegrationTest {

    private static final String NOW = "'2026-10-10T00:00:00.000Z'";

    @Autowired
    private JdbcTemplate jdbc;

    private long worker;
    private long run;
    private long settlement;
    private long version;
    private long payment;

    @BeforeEach
    void setUp() {
        worker = id("INSERT INTO worker (name, worker_type_id, created_at) VALUES ('Schema P5', 1, " + NOW + ")");
        jdbc.update("INSERT INTO employment_period (worker_id, start_date) VALUES (?, '2026-01-01')", worker);
        run = finalizedRun("2026-01-01", "2026-01-31");
        settlement = id("INSERT INTO settlement (worker_id, period_start, created_at) VALUES (" + worker + ", '2026-01-01', " + NOW + ")");
        version = openVersion(settlement, 1, run, 20000);
        payment = id("INSERT INTO payment (worker_id, paid_on, amount_paise, method, allocation_mode, recorded_at) VALUES ("
                + worker + ", '2026-02-01', 30000, 'CASH', 'EXPLICIT', " + NOW + ")");
    }

    private long id(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long finalizedRun(String start, String end) {
        long id = id("INSERT INTO earning_run (worker_id, period_start, period_end, status, engine_version, calculated_at, total_paise, "
                + "needs_review_count, blocker_count) VALUES (" + worker + ", '" + start + "', '" + end + "', 'DRAFT', 1, " + NOW + ", 20000, 0, 0)");
        jdbc.update("UPDATE earning_run SET status = 'FINALIZED', finalized_at = " + NOW + " WHERE id = ?", id);
        return id;
    }

    private long openVersion(long settlementId, int no, long runId, long net) {
        return id("INSERT INTO settlement_version (settlement_id, version_no, earning_run_id, status, rounding_mode, earnings_paise, "
                + "negative_adjustments_paise, shortfall_paise, positive_adjustments_paise, recovery_paise, rounding_paise, net_payable_paise, "
                + "shortfall_treatment, settle_treatment, calculated_at) VALUES (" + settlementId + ", " + no + ", " + runId + ", 'OPEN', "
                + "'EXACT', " + net + ", 0, 0, 0, 0, 0, " + net + ", 'NONE', 'NONE', " + NOW + ")");
    }

    private void settleVersion(long versionId) {
        jdbc.update("UPDATE settlement_version SET status = 'SETTLED', settle_treatment = 'NONE', outstanding_at_settle_paise = 0, "
                + "settled_at = " + NOW + " WHERE id = ?", versionId);
    }

    private long allocate(long paymentId, long amount) {
        return id("INSERT INTO payment_allocation (payment_id, settlement_id, amount_paise, kind, allocated_at) VALUES ("
                + paymentId + ", " + settlement + ", " + amount + ", 'EXPLICIT', " + NOW + ")");
    }

    private void rejected(Runnable action, String message) {
        assertThatThrownBy(action::run).rootCause().isInstanceOf(SQLiteException.class).hasMessageContaining(message);
    }

    private void rejectedCode(Runnable action, SQLiteErrorCode code) {
        assertThatThrownBy(action::run).rootCause()
                .isInstanceOfSatisfying(SQLiteException.class, e -> assertThat(e.getResultCode()).isEqualTo(code));
    }

    // --- payments and allocations ---

    @Test
    void paymentsAreImmutableAndPositive() {
        rejected(() -> jdbc.update("UPDATE payment SET amount_paise = 1 WHERE id = ?", payment), "cannot be changed");
        rejected(() -> jdbc.update("DELETE FROM payment WHERE id = ?", payment), "cannot be deleted");
        rejectedCode(() -> id("INSERT INTO payment (worker_id, paid_on, amount_paise, method, allocation_mode, recorded_at) VALUES ("
                + worker + ", '2026-02-01', -5, 'CASH', 'AUTO', " + NOW + ")"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        rejectedCode(() -> id("INSERT INTO payment (worker_id, paid_on, amount_paise, method, allocation_mode, recorded_at) VALUES ("
                + worker + ", '2026-02-01', 100, 'CHEQUE', 'AUTO', " + NOW + ")"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
    }

    @Test
    void allocationsNeverExceedThePaymentOrTheOutstandingBalance() {
        allocate(payment, 15000);
        rejected(() -> allocate(payment, 6000), "payable outstanding");
        allocate(payment, 5000);
        long other = id("INSERT INTO payment (worker_id, paid_on, amount_paise, method, allocation_mode, recorded_at) VALUES ("
                + worker + ", '2026-02-01', 1000, 'CASH', 'EXPLICIT', " + NOW + ")");
        rejected(() -> allocate(other, 1), "payable outstanding");
        long big = id("INSERT INTO payment (worker_id, paid_on, amount_paise, method, allocation_mode, recorded_at) VALUES ("
                + worker + ", '2026-02-01', 100, 'CASH', 'EXPLICIT', " + NOW + ")");
        jdbc.update("UPDATE settlement_version SET net_payable_paise = 50000, earnings_paise = 50000 WHERE id = ?", version);
        rejected(() -> allocate(big, 200), "cannot exceed the payment");
    }

    @Test
    void allocationsCannotBeChangedAndSettledOnesCannotBeReversed() {
        long allocation = allocate(payment, 10000);
        rejected(() -> jdbc.update("UPDATE payment_allocation SET amount_paise = 1 WHERE id = ?", allocation), "cannot be changed");
        rejected(() -> jdbc.update("DELETE FROM payment_allocation WHERE id = ?", allocation), "cannot be deleted");
        settleVersion(version);
        rejected(() -> id("INSERT INTO allocation_reversal (allocation_id, reason, reversed_at) VALUES (" + allocation + ", 'x', " + NOW + ")"),
                "cannot be reversed");
    }

    @Test
    void voidingNeedsReversedAllocationsAndBlocksFurtherAllocation() {
        long allocation = allocate(payment, 10000);
        rejected(() -> id("INSERT INTO payment_void (payment_id, reason, voided_at) VALUES (" + payment + ", 'x', " + NOW + ")"),
                "after its allocations are reversed");
        id("INSERT INTO allocation_reversal (allocation_id, reason, reversed_at) VALUES (" + allocation + ", 'Mistake', " + NOW + ")");
        id("INSERT INTO payment_void (payment_id, reason, voided_at) VALUES (" + payment + ", 'Mistake', " + NOW + ")");
        rejected(() -> allocate(payment, 100), "voided payment");
    }

    // --- advances and recoveries ---

    @Test
    void recoveriesNeverExceedTheAdvanceOrRepeat() {
        long advance = id("INSERT INTO advance (worker_id, given_on, amount_paise, source, created_at) VALUES (" + worker
                + ", '2026-01-10', 10000, 'PAYMENT_EXCESS', " + NOW + ")");
        String insert = "INSERT INTO advance_recovery (advance_id, settlement_id, settlement_version_id, amount_paise, recovered_at) VALUES ("
                + advance + ", " + settlement + ", " + version + ", %d, " + NOW + ")";
        rejected(() -> id(String.format(insert, 10001)), "cannot exceed the advance");
        long recovery = id(String.format(insert, 6000));
        rejected(() -> id(String.format(insert, 1000)), "repeat an active recovery");
        rejected(() -> jdbc.update("DELETE FROM advance_recovery WHERE id = ?", recovery), "cannot be deleted");
        settleVersion(version);
        rejected(() -> id("INSERT INTO recovery_reversal (recovery_id, reason, reversed_at) VALUES (" + recovery + ", 'x', " + NOW + ")"),
                "only be reversed while its month is being settled again");
    }

    // --- settlement versions and items ---

    @Test
    void settlementVersionsFollowTheirLifecycle() {
        rejected(() -> openVersion(settlement, 2, run, 0), "UNIQUE");
        rejectedCode(() -> jdbc.update("UPDATE settlement_version SET net_payable_paise = 999 WHERE id = ?", version),
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        rejectedCode(() -> jdbc.update("UPDATE settlement_version SET net_payable_paise = -1, earnings_paise = -1 WHERE id = ?", version),
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        settleVersion(version);
        rejected(() -> jdbc.update("UPDATE settlement_version SET rounding_mode = 'UP' WHERE id = ?", version), "reopen it instead");
        rejected(() -> jdbc.update("UPDATE settlement_version SET status = 'OPEN', settle_treatment = 'NONE' WHERE id = ?", version),
                "reopen it instead");
        jdbc.update("UPDATE settlement_version SET status = 'SUPERSEDED', superseded_at = " + NOW + ", superseded_reason = 'Reopened' "
                + "WHERE id = ?", version);
        rejected(() -> jdbc.update("UPDATE settlement_version SET superseded_reason = 'Other' WHERE id = ?", version), "reopen it instead");
        rejected(() -> jdbc.update("DELETE FROM settlement_version WHERE id = ?", version), "cannot be deleted");
    }

    @Test
    void aVersionMustUseTheActiveFinalizedRunOfItsMonth() {
        jdbc.update("UPDATE settlement_version SET status = 'SUPERSEDED', superseded_at = " + NOW + ", superseded_reason = 'x' WHERE id = ?",
                version);
        id("INSERT INTO earning_run_supersession (superseded_run_id, reason, reopened_at) VALUES (" + run + ", 'Reopened', " + NOW + ")");
        rejected(() -> openVersion(settlement, 2, run, 20000), "active finalized earnings");
        long february = finalizedRun("2026-02-01", "2026-02-28");
        rejected(() -> openVersion(settlement, 2, february, 20000), "active finalized earnings");
    }

    @Test
    void itemsChangeOnlyWhileOpen() {
        long item = id("INSERT INTO settlement_item (version_id, line_no, item_type, amount_paise, earning_run_id, explanation) VALUES ("
                + version + ", 1, 'EARNINGS', 20000, " + run + ", 'Earnings')");
        rejectedCode(() -> id("INSERT INTO settlement_item (version_id, line_no, item_type, amount_paise, explanation) VALUES ("
                + version + ", 2, 'UNPAID_TIME_INFO', 5, 'x')"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        settleVersion(version);
        rejected(() -> jdbc.update("UPDATE settlement_item SET amount_paise = 1 WHERE id = ?", item), "only change while the settlement is open");
        rejected(() -> jdbc.update("DELETE FROM settlement_item WHERE id = ?", item), "only change while the settlement is open");
    }

    // --- adjustments ---

    private String adjustment(String kind, long amount, String target, String owed, int cover, int shortfall, String reverses) {
        return "INSERT INTO financial_adjustment (worker_id, kind, amount_paise, reason, adjustment_date, source_settlement_id, "
                + "source_version_id, target_period_start, money_owed_by_worker, affects_source_cover, affects_source_shortfall, reverses_id, "
                + "created_at) VALUES (" + worker + ", '" + kind + "', " + amount + ", 'reason', '2026-02-01', " + settlement + ", " + version
                + ", " + target + ", " + owed + ", " + cover + ", " + shortfall + ", " + reverses + ", " + NOW + ")";
    }

    @Test
    void carryTargetsMustBeLaterUnsettledMonthsAndCarryOnlyOnce() {
        rejected(() -> id(adjustment("CARRY_FORWARD", 1000, "'2026-01-01'", "NULL", 1, 0, "NULL")), "later than the source month");
        long carry = id(adjustment("CARRY_FORWARD", 1000, "'2026-02-01'", "NULL", 1, 0, "NULL"));
        rejected(() -> id(adjustment("CARRY_FORWARD", 500, "'2026-03-01'", "NULL", 1, 0, "NULL")), "already carried");
        rejected(() -> jdbc.update("UPDATE financial_adjustment SET amount_paise = 1 WHERE id = ?", carry), "cannot be changed");
        rejected(() -> jdbc.update("DELETE FROM financial_adjustment WHERE id = ?", carry), "cannot be deleted");

        long febRun = finalizedRun("2026-02-01", "2026-02-28");
        long febSettlement = id("INSERT INTO settlement (worker_id, period_start, created_at) VALUES (" + worker + ", '2026-02-01', " + NOW + ")");
        settleVersion(openVersion(febSettlement, 1, febRun, 20000));
        rejected(() -> id(adjustment("REVERSAL", -1000, "'2026-02-01'", "NULL", 1, 0, String.valueOf(carry))), "not already settled");
    }

    @Test
    void negativeAdjustmentsDeclareMoneyOwedAndReversalsMirror() {
        rejectedCode(() -> id(adjustment("CORRECTION", -500, "'2026-02-01'", "NULL", 0, 0, "NULL")), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        rejectedCode(() -> id(adjustment("OVERPAYMENT_CARRY", -500, "'2026-02-01'", "0", 1, 0, "NULL")), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        rejectedCode(() -> id(adjustment("CARRY_CORRECTION", -500, "'2026-02-01'", "1", 1, 0, "NULL")), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        long owed = id(adjustment("OVERPAYMENT_CARRY", -500, "'2026-02-01'", "1", 1, 0, "NULL"));
        rejected(() -> id(adjustment("REVERSAL", 600, "'2026-02-01'", "1", 1, 0, String.valueOf(owed))), "mirror");
        rejected(() -> id(adjustment("REVERSAL", -100, "'2026-02-01'", "1", 1, 0, String.valueOf(owed))), "mirror");
        id(adjustment("REVERSAL", 200, "'2026-02-01'", "1", 1, 0, String.valueOf(owed)));
        rejectedCode(() -> id(adjustment("REVERSAL", 100, "'2026-02-01'", "1", 1, 0, String.valueOf(owed))), SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
    }

    // --- supersession and the re-created V8 triggers ---

    @Test
    void onlyFinalizedRunsCanBeSupersededAndOneRunStaysActive() {
        long draft = id("INSERT INTO earning_run (worker_id, period_start, period_end, status, engine_version, calculated_at, total_paise, "
                + "needs_review_count, blocker_count) VALUES (" + worker + ", '2026-03-01', '2026-03-31', 'DRAFT', 1, " + NOW + ", 0, 0, 0)");
        rejected(() -> id("INSERT INTO earning_run_supersession (superseded_run_id, reason, reopened_at) VALUES (" + draft + ", 'x', " + NOW + ")"),
                "Only finalized earnings can be reopened");
        rejected(() -> finalizedRun("2026-01-01", "2026-01-31"), "Only one active earnings calculation");

        long reopening = id("INSERT INTO earning_run_supersession (superseded_run_id, reason, reopened_at) VALUES (" + run + ", 'Fix', " + NOW + ")");
        long replacement = id("INSERT INTO earning_run (worker_id, period_start, period_end, status, engine_version, calculated_at, total_paise, "
                + "needs_review_count, blocker_count) VALUES (" + worker + ", '2026-01-01', '2026-01-31', 'DRAFT', 1, " + NOW + ", 0, 0, 0)");
        rejected(() -> id("INSERT INTO earning_run (worker_id, period_start, period_end, status, engine_version, calculated_at, total_paise, "
                + "needs_review_count, blocker_count) VALUES (" + worker + ", '2026-01-01', '2026-01-31', 'DRAFT', 1, " + NOW + ", 0, 0, 0)"),
                "Only one active earnings calculation");
        rejected(() -> jdbc.update("UPDATE earning_run_supersession SET replacement_run_id = ? WHERE id = ?", draft, reopening), "cannot be changed");
        jdbc.update("UPDATE earning_run_supersession SET replacement_run_id = ? WHERE id = ?", replacement, reopening);
        rejected(() -> jdbc.update("UPDATE earning_run_supersession SET replacement_run_id = NULL WHERE id = ?", reopening), "cannot be changed");
        rejected(() -> jdbc.update("DELETE FROM earning_run_supersession WHERE id = ?", reopening), "cannot be deleted");
    }

    @Test
    void locksFollowOnlyTheActiveFinalizedRun() {
        long task = id("INSERT INTO task (name) VALUES ('Schema P5 task')");
        String insertWork = "INSERT INTO work_record (worker_id, work_date, task_id, outcome, recorded_at) VALUES (" + worker + ", '%s', "
                + task + ", 'COMPLETED', " + NOW + ")";
        rejected(() -> id(String.format(insertWork, "2026-01-20")), "finalized");

        id("INSERT INTO earning_run_supersession (superseded_run_id, reason, reopened_at) VALUES (" + run + ", 'Fix', " + NOW + ")");
        id(String.format(insertWork, "2026-01-20"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_record WHERE worker_id = ?", Long.class, worker)).isEqualTo(1L);
    }
}
