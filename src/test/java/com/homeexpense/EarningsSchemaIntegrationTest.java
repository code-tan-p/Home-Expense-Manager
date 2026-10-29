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

/** Proves V8's constraints and triggers with raw SQL that bypasses the services. */
@Transactional
class EarningsSchemaIntegrationTest extends SqliteIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long worker;
    private long task;
    private long rate;
    private long workRecord;

    @BeforeEach
    void setUp() {
        worker = id("INSERT INTO worker (name, worker_type_id, created_at) VALUES ('Schema P4', 1, '2026-01-01T00:00:00.000Z')");
        jdbc.update("INSERT INTO employment_period (worker_id, start_date) VALUES (?, '2026-01-01')", worker);
        task = id("INSERT INTO task (name) VALUES ('Schema P4 task')");
        rate = id("INSERT INTO pay_rate (worker_id, rate_type, amount_paise, effective_from) VALUES (" + worker + ", 'PER_TASK', 5000, '2026-01-01')");
        workRecord = id("INSERT INTO work_record (worker_id, work_date, task_id, outcome, recorded_at) VALUES ("
                + worker + ", '2026-01-05', " + task + ", 'COMPLETED', '2026-01-05T12:00:00.000Z')");
    }

    private long id(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long run(String start, String end, String status) {
        return id("INSERT INTO earning_run (worker_id, period_start, period_end, status, engine_version, calculated_at, "
                + "finalized_at, total_paise, needs_review_count, blocker_count) VALUES (" + worker + ", '" + start + "', '" + end
                + "', 'DRAFT', 1, '2026-02-01T00:00:00.000Z', NULL, 5000, 0, 0)")
                + (status.equals("FINALIZED") ? finalizeRun(start) : 0);
    }

    private long finalizeRun(String start) {
        jdbc.update("UPDATE earning_run SET status = 'FINALIZED', finalized_at = '2026-02-02T00:00:00.000Z' WHERE worker_id = ? AND period_start = ?",
                worker, start);
        return 0;
    }

    private long line(long runId, String type, String date, Long recordId, Long amount, String review) {
        jdbc.update("INSERT INTO earning_line (run_id, line_no, line_type, basis, work_date, work_record_id, pay_rate_id, "
                + "rate_amount_paise, amount_paise, review_status, review_key, explanation) VALUES (?, "
                + "(SELECT IFNULL(MAX(line_no), 0) + 1 FROM earning_line WHERE run_id = ?), ?, 'WORK', ?, ?, ?, 5000, ?, ?, ?, 'test')",
                runId, runId, type, date, recordId, rate, amount, review, review.equals("NONE") ? null : "K");
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void rejected(Runnable action, String message) {
        assertThatThrownBy(action::run).rootCause().isInstanceOf(SQLiteException.class).hasMessageContaining(message);
    }

    private void rejectedCode(Runnable action, SQLiteErrorCode code) {
        assertThatThrownBy(action::run).rootCause()
                .isInstanceOfSatisfying(SQLiteException.class, e -> assertThat(e.getResultCode()).isEqualTo(code));
    }

    @Test
    void runPeriodMustBeAWholeCalendarMonth() {
        rejectedCode(() -> run("2026-01-02", "2026-02-01", "DRAFT"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        rejectedCode(() -> run("2026-02-01", "2026-02-29", "DRAFT"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        run("2028-02-01", "2028-02-29", "DRAFT");
    }

    @Test
    void oneRunPerWorkerAndMonth() {
        // Since V9 this is a trigger (one *active* run), so a reopened month can get a replacement run.
        run("2026-01-01", "2026-01-31", "DRAFT");
        rejected(() -> run("2026-01-01", "2026-01-31", "DRAFT"), "Only one active earnings calculation");
    }

    @Test
    void amountsAreNonNegativeIntegerPaiseAndOnlyMissingWhileUnderReview() {
        long r = run("2026-01-01", "2026-01-31", "DRAFT");
        rejectedCode(() -> line(r, "PER_TASK", "2026-01-05", null, -1L, "NONE"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        rejectedCode(() -> line(r, "PER_TASK", "2026-01-05", null, null, "NONE"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        rejectedCode(() -> line(r, "PER_TASK", "2026-01-05", null, 100L, "NEEDS_REVIEW"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        rejectedCode(() -> jdbc.update("INSERT INTO earning_line (run_id, line_no, line_type, basis, work_date, amount_paise, review_status, explanation) "
                + "VALUES (?, 9, 'PER_TASK', 'WORK', '2026-01-05', 12.5, 'NONE', 'x')", r), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        line(r, "PER_TASK", "2026-01-05", null, null, "NEEDS_REVIEW");
    }

    @Test
    void atMostOneDayLinePerDateAndOneLinePerSourceRecord() {
        long r = run("2026-01-01", "2026-01-31", "DRAFT");
        line(r, "PER_DAY", "2026-01-05", null, 30000L, "NONE");
        rejectedCode(() -> line(r, "PER_DAY", "2026-01-05", null, 30000L, "NONE"), SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
        rejectedCode(() -> line(r, "EXTRA_DAY", "2026-01-05", null, 25000L, "NONE"), SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);

        line(r, "PER_TASK", "2026-01-05", workRecord, 5000L, "NONE");
        rejectedCode(() -> line(r, "EXTRA_TASK", "2026-01-05", workRecord, 5000L, "NONE"), SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
    }

    @Test
    void eachRecordIsTreatedOncePerRun() {
        long r = run("2026-01-01", "2026-01-31", "DRAFT");
        String sql = "INSERT INTO earning_record_treatment (run_id, work_record_id, treatment, review_status, detail) VALUES (?, ?, ?, 'NONE', 'x')";
        jdbc.update(sql, r, workRecord, "PAID");
        rejectedCode(() -> jdbc.update(sql, r, workRecord, "COVERED_BY_DAY_RATE"), SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
    }

    @Test
    void finalizedRunsAndTheirLinesCannotChange() {
        long r = run("2026-01-01", "2026-01-31", "DRAFT");
        long l = line(r, "PER_TASK", "2026-01-05", workRecord, 5000L, "NONE");
        finalizeRun("2026-01-01");

        rejected(() -> jdbc.update("UPDATE earning_line SET amount_paise = 6000 WHERE id = ?", l), "only be changed while the calculation is a draft");
        rejected(() -> jdbc.update("DELETE FROM earning_line WHERE id = ?", l), "only be changed while the calculation is a draft");
        rejected(() -> line(r, "PER_TASK", "2026-01-12", null, 5000L, "NONE"), "only be changed while the calculation is a draft");
        rejected(() -> jdbc.update("UPDATE earning_run SET total_paise = 1 WHERE id = ?", r), "Finalized earnings cannot be changed");
        rejected(() -> jdbc.update("UPDATE earning_run SET status = 'DRAFT', finalized_at = NULL WHERE id = ?", r), "Finalized earnings cannot be changed");
        rejected(() -> jdbc.update("DELETE FROM earning_run WHERE id = ?", r), "cannot be deleted");
    }

    @Test
    void finalizedMonthLocksWorkRecordsAndRates() {
        long r = run("2026-01-01", "2026-01-31", "DRAFT");
        line(r, "PER_TASK", "2026-01-05", workRecord, 5000L, "NONE");
        finalizeRun("2026-01-01");

        rejected(() -> jdbc.update("INSERT INTO work_record (worker_id, work_date, task_id, outcome, recorded_at) VALUES (?, '2026-01-20', ?, "
                + "'COMPLETED', '2026-01-20T12:00:00.000Z')", worker, task), "finalized");
        rejected(() -> jdbc.update("UPDATE work_record SET voided_at = '2026-02-05T00:00:00.000Z', void_reason = 'x' WHERE id = ?", workRecord), "finalized");
        rejected(() -> jdbc.update("UPDATE pay_rate SET amount_paise = 6000 WHERE id = ?", rate), "used by finalized earnings");
        rejected(() -> jdbc.update("UPDATE pay_rate SET effective_to = '2026-01-04' WHERE id = ?", rate), "used by finalized earnings");

        jdbc.update("UPDATE pay_rate SET effective_to = '2026-03-31' WHERE id = ?", rate);
        jdbc.update("INSERT INTO work_record (worker_id, work_date, task_id, outcome, recorded_at) VALUES (?, '2026-02-02', ?, 'COMPLETED', "
                + "'2026-02-02T12:00:00.000Z')", worker, task);
    }

    @Test
    void reviewDecisionsAndConfirmationsKeepTheirHistory() {
        long review = id("INSERT INTO earning_review (worker_id, review_key, decision, suggestion_snapshot, decided_at) VALUES ("
                + worker + ", 'K', 'ACCEPT_SUGGESTED', '2500', '2026-02-01T00:00:00.000Z')");
        rejected(() -> jdbc.update("UPDATE earning_review SET decision = 'OVERRIDE' WHERE id = ?", review), "cannot be changed");
        rejected(() -> jdbc.update("DELETE FROM earning_review WHERE id = ?", review), "cannot be deleted");
        rejectedCode(() -> jdbc.update("INSERT INTO earning_review (worker_id, review_key, decision, suggestion_snapshot, decided_at) VALUES ("
                + worker + ", 'K', 'ACCEPT_SUGGESTED', '2500', '2026-02-01T00:00:00.000Z')"), SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
        rejectedCode(() -> jdbc.update("INSERT INTO earning_review (worker_id, review_key, decision, suggestion_snapshot, decided_at, amount_paise) VALUES ("
                + worker + ", 'K2', 'OVERRIDE', '-', '2026-02-01T00:00:00.000Z', 100)"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);

        jdbc.update("UPDATE earning_review SET superseded_at = '2026-02-02T00:00:00.000Z' WHERE id = ?", review);
        rejected(() -> jdbc.update("UPDATE earning_review SET superseded_at = NULL WHERE id = ?", review), "cannot be changed");

        long confirmation = id("INSERT INTO earning_unrecorded_confirmation (worker_id, period_start, items, fingerprint, statement, confirmed_at) "
                + "VALUES (" + worker + ", '2026-01-01', '2026-01-05#1', 'abc', 'No payable work is missing', '2026-02-01T00:00:00.000Z')");
        rejected(() -> jdbc.update("UPDATE earning_unrecorded_confirmation SET items = 'x' WHERE id = ?", confirmation), "cannot be changed");
        rejected(() -> jdbc.update("DELETE FROM earning_unrecorded_confirmation WHERE id = ?", confirmation), "cannot be deleted");
    }
}
