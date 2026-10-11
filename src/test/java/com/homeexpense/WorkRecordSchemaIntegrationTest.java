package com.homeexpense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * Proves the database itself enforces the Phase 3 rules for work records, using raw SQL that
 * bypasses the services. 2026-01-05 is a Monday and 2026-01-06 a Tuesday.
 */
@Transactional
class WorkRecordSchemaIntegrationTest extends SqliteIntegrationTest {

    private static final String MON = "2026-01-05";
    private static final String TUE = "2026-01-06";

    @Autowired
    private JdbcTemplate jdbc;

    private long worker;
    private long otherWorker;
    private long sweeping;
    private long milk;
    private long morning;
    private long schedule;
    private long monSweeping;
    private long monMorning;
    private long tueMilk;

    @BeforeEach
    void setUp() {
        worker = insertWorker("Sunita");
        otherWorker = insertWorker("Asha");
        sweeping = insertReturningId("INSERT INTO task (name) VALUES ('Schema P3 sweeping')");
        milk = insertReturningId("INSERT INTO task (name) VALUES ('Schema P3 milk')");
        morning = insertReturningId("INSERT INTO shift (name, default_start, default_end) VALUES ('Schema P3 morning', '07:00', '09:00')");
        schedule = insertReturningId("INSERT INTO work_schedule (worker_id, effective_from) VALUES (" + worker + ", '2026-01-01')");
        monSweeping = insertReturningId("INSERT INTO schedule_entry (schedule_id, day_of_week, task_id) VALUES (" + schedule + ", 1, " + sweeping + ")");
        monMorning = insertReturningId("INSERT INTO schedule_entry (schedule_id, day_of_week, shift_id) VALUES (" + schedule + ", 1, " + morning + ")");
        tueMilk = insertReturningId("INSERT INTO schedule_entry (schedule_id, day_of_week, task_id, expected_quantity_milli) VALUES ("
                + schedule + ", 2, " + milk + ", 1500)");
    }

    private long insertWorker(String name) {
        long id = insertReturningId("INSERT INTO worker (name, worker_type_id, created_at) VALUES ('" + name
                + "', (SELECT id FROM worker_type WHERE name = 'Maid'), '2026-01-01T00:00:00.000Z')");
        jdbc.update("INSERT INTO employment_period (worker_id, start_date) VALUES (?, '2026-01-01')", id);
        return id;
    }

    private long insertReturningId(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /** Inserts a work record from column/value pairs; recorded_at is filled in. */
    private long record(Object... pairs) {
        List<String> columns = new ArrayList<>(List.of("recorded_at"));
        List<Object> values = new ArrayList<>(List.of("2026-01-05T12:00:00.000Z"));
        for (int i = 0; i < pairs.length; i += 2) {
            columns.add((String) pairs[i]);
            values.add(pairs[i + 1]);
        }
        String sql = "INSERT INTO work_record (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
        jdbc.update(sql, values.toArray());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private long completedSweeping(String date) {
        return record("worker_id", worker, "work_date", date, "schedule_entry_id", monSweeping,
                "task_id", sweeping, "outcome", "COMPLETED");
    }

    private void assertRejected(Runnable action, SQLiteErrorCode expected) {
        assertThatThrownBy(action::run)
                .rootCause()
                .isInstanceOfSatisfying(SQLiteException.class, e -> assertThat(e.getResultCode()).isEqualTo(expected));
    }

    private void assertRejectedWithMessage(Runnable action, String messagePart) {
        assertThatThrownBy(action::run).rootCause().isInstanceOf(SQLiteException.class).hasMessageContaining(messagePart);
    }

    // --- weekday and schedule-version consistency ---

    @Test
    void sqliteWeekdayNumbersAreIso() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT CAST(strftime('%u', '2026-01-05') AS INTEGER) AS mon, CAST(strftime('%u', '2026-01-04') AS INTEGER) AS sun");
        assertThat(row).containsEntry("mon", 1).containsEntry("sun", 7);
    }

    @Test
    void scheduledRecordMustBeOnTheEntrysWeekday() {
        completedSweeping(MON);

        assertRejectedWithMessage(() -> completedSweeping(TUE), "does not apply to this worker on this date");
    }

    @Test
    void scheduledRecordMustBelongToTheSameWorker() {
        assertRejectedWithMessage(() -> record("worker_id", otherWorker, "work_date", MON, "schedule_entry_id", monSweeping,
                "task_id", sweeping, "outcome", "COMPLETED"), "does not apply to this worker");
    }

    @Test
    void scheduledRecordMustCopyTheEntrysActivity() {
        assertRejectedWithMessage(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping,
                "task_id", milk, "outcome", "COMPLETED"), "does not apply");
    }

    @Test
    void scheduledRecordMustUseTheVersionInEffectOnThatDate() {
        jdbc.update("UPDATE work_schedule SET effective_to = '2026-01-31' WHERE id = ?", schedule);
        insertReturningId("INSERT INTO work_schedule (worker_id, effective_from) VALUES (" + worker + ", '2026-02-01')");

        completedSweeping("2026-01-26");
        assertRejectedWithMessage(() -> completedSweeping("2026-02-02"), "does not apply");
    }

    @Test
    void workMustBeWithinEmployment() {
        assertRejectedWithMessage(() -> record("worker_id", worker, "work_date", "2025-12-31", "task_id", sweeping,
                "outcome", "COMPLETED"), "employed");
    }

    // --- shape of a record ---

    @Test
    void outcomeAndReasonMustAgree() {
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "NOT_DONE"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "COMPLETED", "absence_reason", "APPROVED_LEAVE"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "SKIPPED"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
    }

    @Test
    void onlyUnauthorizedAbsenceCarriesADeductionDecision() {
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "NOT_DONE", "absence_reason", "UNAUTHORIZED_ABSENCE"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "NOT_DONE", "absence_reason", "APPROVED_LEAVE", "deduction_decision", "PENDING"),
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);

        record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "NOT_DONE", "absence_reason", "UNAUTHORIZED_ABSENCE", "deduction_decision", "PENDING");
    }

    @Test
    void newRecordsMustBeCurrentWithAPendingDecision() {
        assertRejectedWithMessage(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping,
                "task_id", sweeping, "outcome", "NOT_DONE", "absence_reason", "UNAUTHORIZED_ABSENCE",
                "deduction_decision", "APPROVED"), "pending");
        assertRejectedWithMessage(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping,
                "task_id", sweeping, "outcome", "COMPLETED", "voided_at", "2026-01-05T12:00:00.000Z", "void_reason", "x"),
                "must be current");
    }

    @Test
    void notDoneHasNoMeasurementsAndNeedsAScheduledActivity() {
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "NOT_DONE", "absence_reason", "APPROVED_LEAVE", "actual_minutes", 30), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "task_id", sweeping,
                "outcome", "NOT_DONE", "absence_reason", "APPROVED_LEAVE"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
    }

    @Test
    void timesMustBeValidAndAgreeWithMinutes() {
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "task_id", sweeping, "outcome", "COMPLETED",
                "actual_start", "07:00", "actual_end", "08:00", "actual_minutes", 45), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "task_id", sweeping, "outcome", "COMPLETED",
                "actual_start", "23:00", "actual_end", "24:00", "actual_minutes", 60), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "task_id", sweeping, "outcome", "COMPLETED",
                "actual_start", "07:00", "actual_minutes", 60), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "task_id", sweeping, "outcome", "COMPLETED",
                "actual_start", "07:00", "actual_end", "08:00"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);

        record("worker_id", worker, "work_date", MON, "task_id", sweeping, "outcome", "COMPLETED",
                "actual_start", "07:00", "actual_end", "08:30", "actual_minutes", 90);
    }

    @Test
    void partialWorkMustBeBelowTheExpectationOrExplained() {
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monMorning, "shift_id", morning,
                "outcome", "PARTIAL", "expected_minutes", 120, "actual_minutes", 120), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        assertRejected(() -> record("worker_id", worker, "work_date", TUE, "schedule_entry_id", tueMilk, "task_id", milk,
                "outcome", "PARTIAL", "expected_quantity_milli", 1500, "actual_quantity_milli", 1500), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "PARTIAL"), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
        assertRejected(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "PARTIAL", "note", "   "), SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);

        record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "PARTIAL", "note", "Only the front rooms");
        record("worker_id", worker, "work_date", MON, "schedule_entry_id", monMorning, "shift_id", morning,
                "outcome", "PARTIAL", "expected_minutes", 120, "actual_minutes", 60);
    }

    // --- one current answer per expected activity ---

    @Test
    void onlyOneCurrentRecordPerExpectedActivity() {
        long first = completedSweeping(MON);

        assertRejected(() -> completedSweeping(MON), SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);

        jdbc.update("UPDATE work_record SET voided_at = '2026-01-06T00:00:00.000Z', void_reason = 'Mistake' WHERE id = ?", first);
        completedSweeping(MON);
    }

    // --- immutability ---

    @Test
    void recordedFactsCannotBeChanged() {
        long id = record("worker_id", worker, "work_date", MON, "task_id", sweeping, "outcome", "COMPLETED", "note", "Original");

        for (String change : List.of("outcome = 'PARTIAL'", "note = 'Edited'", "work_date = '2026-01-12'",
                "actual_minutes = 30", "task_id = " + milk, "recorded_at = '2027-01-01T00:00:00.000Z'")) {
            assertRejectedWithMessage(() -> jdbc.update("UPDATE work_record SET " + change + " WHERE id = ?", id), "cannot be changed");
        }
        assertThat(jdbc.queryForObject("SELECT note FROM work_record WHERE id = ?", String.class, id)).isEqualTo("Original");
    }

    @Test
    void voidingHappensOnceAndCannotBeUndone() {
        long id = completedSweeping(MON);
        jdbc.update("UPDATE work_record SET voided_at = '2026-01-06T00:00:00.000Z', void_reason = 'Mistake' WHERE id = ?", id);

        assertRejectedWithMessage(() -> jdbc.update("UPDATE work_record SET voided_at = NULL, void_reason = NULL WHERE id = ?", id),
                "cannot be changed");
        assertRejectedWithMessage(() -> jdbc.update("UPDATE work_record SET void_reason = 'Other' WHERE id = ?", id),
                "cannot be changed");
        assertRejected(() -> jdbc.update("UPDATE work_record SET voided_at = '2026-01-07T00:00:00.000Z' WHERE id = ? ", id),
                SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER);
    }

    @Test
    void deductionDecisionMovesOnceFromPending() {
        long id = record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "NOT_DONE", "absence_reason", "UNAUTHORIZED_ABSENCE", "deduction_decision", "PENDING");

        jdbc.update("UPDATE work_record SET deduction_decision = 'APPROVED' WHERE id = ?", id);
        assertRejectedWithMessage(() -> jdbc.update("UPDATE work_record SET deduction_decision = 'WAIVED' WHERE id = ?", id),
                "only be made once");
        assertRejectedWithMessage(() -> jdbc.update("UPDATE work_record SET deduction_decision = 'PENDING' WHERE id = ?", id),
                "only be made once");
    }

    @Test
    void noDecisionOnAVoidedRecord() {
        long id = record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "NOT_DONE", "absence_reason", "UNAUTHORIZED_ABSENCE", "deduction_decision", "PENDING");
        jdbc.update("UPDATE work_record SET voided_at = '2026-01-06T00:00:00.000Z', void_reason = 'Mistake' WHERE id = ?", id);

        assertRejectedWithMessage(() -> jdbc.update("UPDATE work_record SET deduction_decision = 'APPROVED' WHERE id = ?", id),
                "only be made once");
    }

    @Test
    void recordsCannotBeDeleted() {
        long id = completedSweeping(MON);

        assertRejectedWithMessage(() -> jdbc.update("DELETE FROM work_record WHERE id = ?", id), "cannot be deleted");
    }

    // --- correction chains ---

    @Test
    void replacementMustFollowAVoidedRecordOfTheSameActivity() {
        long original = completedSweeping(MON);

        assertRejectedWithMessage(() -> record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping,
                "task_id", sweeping, "outcome", "PARTIAL", "note", "x", "replaces_record_id", original), "must replace a voided record");

        jdbc.update("UPDATE work_record SET voided_at = '2026-01-06T00:00:00.000Z', void_reason = 'Corrected' WHERE id = ?", original);
        assertRejectedWithMessage(() -> record("worker_id", worker, "work_date", "2026-01-12", "schedule_entry_id", monSweeping,
                "task_id", sweeping, "outcome", "COMPLETED", "replaces_record_id", original), "same worker, date and activity");

        long replacement = record("worker_id", worker, "work_date", MON, "schedule_entry_id", monSweeping, "task_id", sweeping,
                "outcome", "PARTIAL", "note", "Only half", "replaces_record_id", original);
        assertThat(replacement).isGreaterThan(original);
    }

    @Test
    void aRecordCanBeReplacedOnlyOnce() {
        long original = record("worker_id", worker, "work_date", MON, "task_id", sweeping, "outcome", "COMPLETED");
        jdbc.update("UPDATE work_record SET voided_at = '2026-01-06T00:00:00.000Z', void_reason = 'Corrected' WHERE id = ?", original);
        long first = record("worker_id", worker, "work_date", MON, "task_id", sweeping, "outcome", "COMPLETED",
                "actual_minutes", 30, "replaces_record_id", original);
        jdbc.update("UPDATE work_record SET voided_at = '2026-01-06T00:00:00.000Z', void_reason = 'Corrected' WHERE id = ?", first);

        assertRejected(() -> record("worker_id", worker, "work_date", MON, "task_id", sweeping, "outcome", "COMPLETED",
                "actual_minutes", 40, "replaces_record_id", original), SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
    }

    // --- protection of recorded work against schedule and employment changes ---

    @Test
    void scheduleVersionCannotBeShortenedPastRecordedWork() {
        completedSweeping(MON);

        assertRejectedWithMessage(() -> jdbc.update("UPDATE work_schedule SET effective_to = '2026-01-04' WHERE id = ?", schedule),
                "Work has been recorded against this schedule");
        jdbc.update("UPDATE work_schedule SET effective_to = '2026-01-05' WHERE id = ?", schedule);
    }

    @Test
    void voidedRecordsDoNotBlockScheduleChanges() {
        long id = completedSweeping(MON);
        jdbc.update("UPDATE work_record SET voided_at = '2026-01-06T00:00:00.000Z', void_reason = 'Mistake' WHERE id = ?", id);

        jdbc.update("UPDATE work_schedule SET effective_to = '2026-01-04' WHERE id = ?", schedule);
        assertThat(jdbc.queryForObject("SELECT effective_to FROM work_schedule WHERE id = ?", String.class, schedule))
                .isEqualTo("2026-01-04");
    }

    @Test
    void employmentCannotBeShortenedPastRecordedWork() {
        completedSweeping(MON);

        assertRejectedWithMessage(() -> jdbc.update(
                "UPDATE employment_period SET end_date = '2026-01-04' WHERE worker_id = ?", worker), "employment change");
        jdbc.update("UPDATE employment_period SET end_date = '2026-01-05' WHERE worker_id = ?", worker);
    }
}
