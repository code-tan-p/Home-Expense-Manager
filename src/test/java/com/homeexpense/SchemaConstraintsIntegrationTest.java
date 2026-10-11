package com.homeexpense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * Proves the database itself enforces the Phase 2 rules, using raw SQL that bypasses the
 * services entirely. Each test runs in a transaction that is rolled back.
 */
@Transactional
class SchemaConstraintsIntegrationTest extends SqliteIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long workerId;
    private long taskA;
    private long taskB;
    private long shiftId;

    @BeforeEach
    void insertBaseRows() {
        jdbc.update("INSERT INTO worker (name, worker_type_id, created_at) "
                + "VALUES ('Sunita', (SELECT id FROM worker_type WHERE name = 'Maid'), '2026-01-01T00:00:00.000Z')");
        workerId = lastId();
        jdbc.update("INSERT INTO task (name) VALUES ('Schema test sweeping')");
        taskA = lastId();
        jdbc.update("INSERT INTO task (name) VALUES ('Schema test mopping')");
        taskB = lastId();
        jdbc.update("INSERT INTO shift (name, default_start, default_end) VALUES ('Schema test morning', '07:00', '09:00')");
        shiftId = lastId();
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void assertRejected(String sql, SQLiteErrorCode expected, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .rootCause()
                .isInstanceOfSatisfying(SQLiteException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(expected));
    }

    // --- worker types and workers ---

    @Test
    void sixBuiltInWorkerTypesAreSeeded() {
        List<String> builtIn = jdbc.queryForList(
                "SELECT name FROM worker_type WHERE built_in = 1 ORDER BY name", String.class);
        assertThat(builtIn).containsExactly("Cleaner", "Cook", "Driver", "Gardener", "Maid", "Milkman");
    }

    @Test
    void workerTypeNamesAreUniqueIgnoringCase() {
        assertRejected("INSERT INTO worker_type (name) VALUES ('MAID')", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
    }

    @Test
    void workerNeedsExistingType() {
        assertRejected("INSERT INTO worker (name, worker_type_id, created_at) VALUES ('X', 9999, '2026-01-01T00:00:00.000Z')",
                SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY);
    }

    @Test
    void workerNameCannotBeBlank() {
        assertRejected("INSERT INTO worker (name, worker_type_id, created_at) VALUES ('   ', 1, '2026-01-01T00:00:00.000Z')",
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);
    }

    // --- employment periods ---

    @Test
    void employmentDatesMustBeRealIsoDates() {
        String sql = "INSERT INTO employment_period (worker_id, start_date) VALUES (?, ?)";
        assertRejected(sql, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, workerId, "2026-02-30");
        assertRejected(sql, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, workerId, "2026-1-5");
        assertRejected(sql, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, workerId, "05/01/2026");
    }

    @Test
    void employmentCannotEndBeforeItStarts() {
        assertRejected("INSERT INTO employment_period (worker_id, start_date, end_date) VALUES (?, '2026-05-10', '2026-05-09')",
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, workerId);
    }

    @Test
    void overlappingEmploymentPeriodsAreRejected() {
        jdbc.update("INSERT INTO employment_period (worker_id, start_date, end_date) VALUES (?, '2026-01-01', '2026-03-31')", workerId);

        assertRejected("INSERT INTO employment_period (worker_id, start_date, end_date) VALUES (?, '2026-03-31', '2026-06-30')",
                SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER, workerId);
    }

    @Test
    void adjacentEmploymentPeriodsAreAllowed() {
        jdbc.update("INSERT INTO employment_period (worker_id, start_date, end_date) VALUES (?, '2026-01-01', '2026-03-31')", workerId);
        jdbc.update("INSERT INTO employment_period (worker_id, start_date) VALUES (?, '2026-04-01')", workerId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employment_period WHERE worker_id = ?", Integer.class, workerId))
                .isEqualTo(2);
    }

    @Test
    void secondOpenEmploymentPeriodIsRejected() {
        jdbc.update("INSERT INTO employment_period (worker_id, start_date) VALUES (?, '2026-01-01')", workerId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO employment_period (worker_id, start_date) VALUES (?, '2027-01-01')", workerId))
                .rootCause()
                .isInstanceOfSatisfying(SQLiteException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT.code));
    }

    @Test
    void updateThatCreatesOverlapIsRejected() {
        jdbc.update("INSERT INTO employment_period (worker_id, start_date, end_date) VALUES (?, '2026-01-01', '2026-03-31')", workerId);
        long first = lastId();
        jdbc.update("INSERT INTO employment_period (worker_id, start_date) VALUES (?, '2026-05-01')", workerId);

        assertRejected("UPDATE employment_period SET end_date = '2026-05-15' WHERE id = ?",
                SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER, first);
    }

    @Test
    void employmentNeedsExistingWorker() {
        assertRejected("INSERT INTO employment_period (worker_id, start_date) VALUES (9999, '2026-01-01')",
                SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY);
    }

    // --- tasks and shifts ---

    @Test
    void taskNamesAreUniqueIgnoringCase() {
        assertRejected("INSERT INTO task (name) VALUES ('SCHEMA TEST SWEEPING')", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
    }

    @Test
    void shiftTimesMustBeValidHoursAndMinutesWithEndAfterStart() {
        String sql = "INSERT INTO shift (name, default_start, default_end) VALUES (?, ?, ?)";
        assertRejected(sql, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, "Bad 1", "7:00", "09:00");
        assertRejected(sql, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, "Bad 2", "07:00", "24:00");
        assertRejected(sql, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, "Overnight", "22:00", "06:00");
        assertRejected(sql, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, "Zero length", "07:00", "07:00");
        assertRejected(sql, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, "With seconds", "07:00:00", "09:00");
    }

    @Test
    void scheduleEntryTimesRejectTwentyFourHundred() {
        long schedule = insertSchedule("2026-01-01", null);
        assertRejected("INSERT INTO schedule_entry (schedule_id, day_of_week, task_id, start_time, end_time) VALUES (?, 1, ?, '22:00', '24:00')",
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, schedule, taskA);
    }

    // --- schedules ---

    private long insertSchedule(String from, String to) {
        jdbc.update("INSERT INTO work_schedule (worker_id, effective_from, effective_to) VALUES (?, ?, ?)", workerId, from, to);
        return lastId();
    }

    @Test
    void overlappingScheduleVersionsAreRejectedButAdjacentAreAllowed() {
        insertSchedule("2026-01-01", "2026-03-31");
        insertSchedule("2026-04-01", null);

        assertRejected("INSERT INTO work_schedule (worker_id, effective_from) VALUES (?, '2026-06-01')",
                SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER, workerId);
    }

    @Test
    void scheduleEntryNeedsTaskOrShift() {
        long schedule = insertSchedule("2026-01-01", null);
        assertRejected("INSERT INTO schedule_entry (schedule_id, day_of_week) VALUES (?, 1)",
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, schedule);
    }

    @Test
    void scheduleEntryValidatesDayAndTimes() {
        long schedule = insertSchedule("2026-01-01", null);
        assertRejected("INSERT INTO schedule_entry (schedule_id, day_of_week, task_id) VALUES (?, 8, ?)",
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, schedule, taskA);
        assertRejected("INSERT INTO schedule_entry (schedule_id, day_of_week, task_id, start_time) VALUES (?, 1, ?, '07:00')",
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, schedule, taskA);
        assertRejected("INSERT INTO schedule_entry (schedule_id, day_of_week, task_id, start_time, end_time) VALUES (?, 1, ?, '09:00', '07:00')",
                SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, schedule, taskA);
        assertRejected("INSERT INTO schedule_entry (schedule_id, day_of_week, task_id, start_time, end_time, duration_minutes) "
                + "VALUES (?, 1, ?, '07:00', '08:00', 60)", SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, schedule, taskA);
    }

    @Test
    void identicalScheduleEntriesAreRejectedButDistinctOnesOnTheSameDayAreAllowed() {
        long schedule = insertSchedule("2026-01-01", null);
        String sql = "INSERT INTO schedule_entry (schedule_id, day_of_week, task_id, shift_id, start_time, end_time) VALUES (?, 1, ?, ?, ?, ?)";
        jdbc.update(sql, schedule, taskA, null, null, null);
        jdbc.update(sql, schedule, taskB, null, null, null);
        jdbc.update(sql, schedule, taskA, null, "17:00", "18:00");
        jdbc.update(sql, schedule, null, shiftId, null, null);

        assertRejected(sql, SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE, schedule, taskA, null, null, null);
    }

    // --- pay arrangements and rates ---

    private static final String INSERT_RATE = "INSERT INTO pay_rate "
            + "(worker_id, rate_type, task_id, shift_id, unit_label, amount_paise, effective_from, effective_to) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    @Test
    void overlappingArrangementsAndUnknownModelsAreRejected() {
        jdbc.update("INSERT INTO pay_arrangement (worker_id, primary_model, effective_from) VALUES (?, 'MONTHLY_SALARY', '2026-01-01')", workerId);

        assertRejected("INSERT INTO pay_arrangement (worker_id, primary_model, effective_from) VALUES (?, 'HOURLY', '2026-06-01')",
                SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER, workerId);
        // Dates chosen so that no overlap exists; triggers run before CHECK constraints.
        assertRejected("INSERT INTO pay_arrangement (worker_id, primary_model, effective_from, effective_to) "
                + "VALUES (?, 'WEEKLY', '2025-01-01', '2025-06-30')", SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK, workerId);
    }

    @Test
    void amountsMustBePositiveIntegerPaise() {
        assertRejected(INSERT_RATE, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK,
                workerId, "MONTHLY_SALARY", null, null, null, 0, "2026-01-01", null);
        assertRejected(INSERT_RATE, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK,
                workerId, "MONTHLY_SALARY", null, null, null, -100, "2026-01-01", null);
        assertRejected(INSERT_RATE, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK,
                workerId, "MONTHLY_SALARY", null, null, null, 115.38, "2026-01-01", null);
    }

    @Test
    void largeAmountsAreStoredExactlyAsIntegers() {
        jdbc.update(INSERT_RATE, workerId, "MONTHLY_SALARY", null, null, null, 5_000_000_000L, "2026-01-01", null);

        assertThat(jdbc.queryForObject("SELECT amount_paise FROM pay_rate WHERE id = ?", Long.class, lastId()))
                .isEqualTo(5_000_000_000L);
    }

    @Test
    void rateShapeRulesAreEnforced() {
        assertRejected(INSERT_RATE, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK,
                workerId, "MONTHLY_SALARY", taskA, null, null, 100, "2026-01-01", null);
        assertRejected(INSERT_RATE, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK,
                workerId, "EXTRA_TASK", null, null, null, 100, "2026-01-01", null);
        assertRejected(INSERT_RATE, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK,
                workerId, "PER_TASK", null, shiftId, null, 100, "2026-01-01", null);
        assertRejected(INSERT_RATE, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK,
                workerId, "PER_UNIT", null, null, null, 100, "2026-01-01", null);
        assertRejected(INSERT_RATE, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK,
                workerId, "HOURLY", null, null, "litre", 100, "2026-01-01", null);
        assertRejected(INSERT_RATE, SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK,
                workerId, "WEEKLY", null, null, null, 100, "2026-01-01", null);
    }

    @Test
    void overlappingRatesWithTheSameKeyAreRejected() {
        jdbc.update(INSERT_RATE, workerId, "PER_TASK", taskA, null, null, 5000, "2026-01-01", null);

        assertRejected(INSERT_RATE, SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER,
                workerId, "PER_TASK", taskA, null, null, 6000, "2026-03-01", null);
    }

    @Test
    void differentTaskSpecificAndStandardRatesMayCoexist() {
        jdbc.update(INSERT_RATE, workerId, "PER_TASK", null, null, null, 4000, "2026-01-01", null);
        jdbc.update(INSERT_RATE, workerId, "PER_TASK", taskA, null, null, 5000, "2026-01-01", null);
        jdbc.update(INSERT_RATE, workerId, "PER_TASK", taskB, null, null, 6000, "2026-01-01", null);
        jdbc.update(INSERT_RATE, workerId, "EXTRA_TASK", taskA, null, null, 7000, "2026-01-01", null);
        jdbc.update(INSERT_RATE, workerId, "PER_SHIFT", null, shiftId, null, 8000, "2026-01-01", null);
        jdbc.update(INSERT_RATE, workerId, "PER_SHIFT", null, null, null, 9000, "2026-01-01", null);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_rate WHERE worker_id = ?", Integer.class, workerId))
                .isEqualTo(6);
    }

    @Test
    void extendingARateIntoTheNextVersionIsRejected() {
        jdbc.update(INSERT_RATE, workerId, "HOURLY", null, null, null, 8000, "2026-01-01", "2026-03-31");
        long first = lastId();
        jdbc.update(INSERT_RATE, workerId, "HOURLY", null, null, null, 9000, "2026-04-01", null);

        assertRejected("UPDATE pay_rate SET effective_to = NULL WHERE id = ?", SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER, first);
    }

    // --- audit ---

    @Test
    void auditEventsCannotBeChangedOrDeleted() {
        jdbc.update("INSERT INTO audit_event (occurred_at, entity_type, entity_id, action) "
                + "VALUES ('2026-01-01T00:00:00.000Z', 'TEST', 1, 'CREATE')");
        long id = lastId();

        assertRejected("UPDATE audit_event SET action = 'EDITED' WHERE id = ?", SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER, id);
        assertRejected("DELETE FROM audit_event WHERE id = ?", SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER, id);
    }
}
