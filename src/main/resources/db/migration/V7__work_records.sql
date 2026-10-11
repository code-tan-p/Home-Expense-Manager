-- Daily work and attendance records.
--
-- Expected activities are not stored: they are worked out from the effective-dated
-- schedules (work_schedule / schedule_entry), which are never edited in place. A
-- work_record is an explicit, confirmed answer to one expected activity
-- (schedule_entry_id + work_date) or a piece of unscheduled work (schedule_entry_id NULL).
--
-- History: records are never edited or deleted. A correction voids the current record
-- and inserts a replacement that points back to it (replaces_record_id), forming a chain.
-- Only the void fields (once) and a pending deduction decision may change after insert.
--
-- No money is stored here; earnings and deductions are calculated in a later phase.
--
-- CHECK constraints pass when they evaluate to NULL, so comparisons involving nullable
-- columns below use IS or IFNULL to make sure a missing value fails the check.

CREATE TABLE work_record (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id               INTEGER NOT NULL REFERENCES worker (id),
    work_date               TEXT    NOT NULL CHECK (date(work_date) IS work_date),
    schedule_entry_id       INTEGER REFERENCES schedule_entry (id),
    task_id                 INTEGER REFERENCES task (id),
    shift_id                INTEGER REFERENCES shift (id),
    outcome                 TEXT    NOT NULL CHECK (outcome IN ('COMPLETED', 'PARTIAL', 'NOT_DONE')),
    absence_reason          TEXT    CHECK (absence_reason IN ('HOUSEHOLD_HOLIDAY', 'APPROVED_LEAVE', 'UNAUTHORIZED_ABSENCE')),
    deduction_decision      TEXT    CHECK (deduction_decision IN ('PENDING', 'APPROVED', 'WAIVED')),
    -- Snapshot of what was expected when the record was made.
    expected_minutes        INTEGER CHECK (expected_minutes IS NULL OR expected_minutes BETWEEN 1 AND 1440),
    expected_quantity_milli INTEGER CHECK (expected_quantity_milli IS NULL OR expected_quantity_milli > 0),
    actual_start            TEXT    CHECK (strftime('%H:%M', actual_start) IS actual_start AND actual_start < '24:00'),
    actual_end              TEXT    CHECK (strftime('%H:%M', actual_end) IS actual_end AND actual_end < '24:00'),
    actual_minutes          INTEGER CHECK (actual_minutes IS NULL OR actual_minutes BETWEEN 1 AND 1440),
    actual_quantity_milli   INTEGER CHECK (actual_quantity_milli IS NULL OR actual_quantity_milli > 0),
    note                    TEXT,
    recorded_at             TEXT    NOT NULL,
    replaces_record_id      INTEGER REFERENCES work_record (id),
    voided_at               TEXT,
    void_reason             TEXT,

    -- Every record identifies an activity.
    CHECK (schedule_entry_id IS NOT NULL OR task_id IS NOT NULL OR shift_id IS NOT NULL),
    -- NOT_DONE always has a reason, and only NOT_DONE has one.
    CHECK ((outcome = 'NOT_DONE') = (absence_reason IS NOT NULL)),
    -- Unauthorized absences, and only those, carry a deduction decision.
    CHECK ((absence_reason IS 'UNAUTHORIZED_ABSENCE') = (deduction_decision IS NOT NULL)),
    -- Nothing was done, so nothing was measured.
    CHECK (outcome <> 'NOT_DONE' OR (actual_start IS NULL AND actual_minutes IS NULL AND actual_quantity_milli IS NULL)),
    -- Absence only makes sense against an expected (scheduled) activity.
    CHECK (schedule_entry_id IS NOT NULL OR outcome <> 'NOT_DONE'),
    -- Times: both or neither, same day, and minutes consistent with them.
    CHECK ((actual_start IS NULL) = (actual_end IS NULL)),
    CHECK (actual_start IS NULL OR (
        actual_end > actual_start
        AND actual_minutes IS (CAST(strftime('%s', '2000-01-01 ' || actual_end) AS INTEGER)
                            - CAST(strftime('%s', '2000-01-01 ' || actual_start) AS INTEGER)) / 60)),
    -- Partial work is measured against the expectation, or explained by a note when there is none.
    CHECK (outcome <> 'PARTIAL' OR actual_minutes IS NOT NULL OR actual_quantity_milli IS NOT NULL
           OR (expected_minutes IS NULL AND expected_quantity_milli IS NULL AND IFNULL(length(trim(note)), 0) > 0)),
    CHECK (outcome <> 'PARTIAL' OR actual_minutes IS NULL OR expected_minutes IS NULL OR actual_minutes < expected_minutes),
    CHECK (outcome <> 'PARTIAL' OR actual_quantity_milli IS NULL OR expected_quantity_milli IS NULL
           OR actual_quantity_milli < expected_quantity_milli),
    CHECK ((voided_at IS NULL) = (void_reason IS NULL))
);

CREATE INDEX idx_work_record_worker_date ON work_record (worker_id, work_date);
CREATE INDEX idx_work_record_schedule_entry ON work_record (schedule_entry_id);

-- One current answer per expected activity. Voided history rows are not counted, and
-- different tasks/shifts on the same day are different schedule entries.
CREATE UNIQUE INDEX uq_work_record_current_expected
    ON work_record (schedule_entry_id, work_date)
    WHERE voided_at IS NULL AND schedule_entry_id IS NOT NULL;

-- A record can be replaced at most once, so corrections form a single chain.
CREATE UNIQUE INDEX uq_work_record_replaces
    ON work_record (replaces_record_id)
    WHERE replaces_record_id IS NOT NULL;

-- New rows are always current, and a deduction decision starts as PENDING.
CREATE TRIGGER trg_work_record_insert_state
BEFORE INSERT ON work_record
WHEN NEW.voided_at IS NOT NULL OR NEW.deduction_decision IS NOT NULL AND NEW.deduction_decision <> 'PENDING'
BEGIN
    SELECT RAISE(ABORT, 'New work records must be current, with any deduction decision pending');
END;

-- Work can only be recorded while the worker was employed.
CREATE TRIGGER trg_work_record_within_employment
BEFORE INSERT ON work_record
WHEN NOT EXISTS (
    SELECT 1 FROM employment_period p
    WHERE p.worker_id = NEW.worker_id
      AND p.start_date <= NEW.work_date
      AND (p.end_date IS NULL OR p.end_date >= NEW.work_date)
)
BEGIN
    SELECT RAISE(ABORT, 'Work can only be recorded on a date the worker was employed');
END;

-- A scheduled record must answer an entry of THIS worker's schedule version in effect on
-- that date, on the matching weekday (strftime('%u'): 1 = Monday ... 7 = Sunday), with the
-- same task and shift as the entry.
CREATE TRIGGER trg_work_record_schedule_applies
BEFORE INSERT ON work_record
WHEN NEW.schedule_entry_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM schedule_entry e
    JOIN work_schedule s ON s.id = e.schedule_id
    WHERE e.id = NEW.schedule_entry_id
      AND s.worker_id = NEW.worker_id
      AND s.effective_from <= NEW.work_date
      AND (s.effective_to IS NULL OR s.effective_to >= NEW.work_date)
      AND e.day_of_week = CAST(strftime('%u', NEW.work_date) AS INTEGER)
      AND IFNULL(e.task_id, 0) = IFNULL(NEW.task_id, 0)
      AND IFNULL(e.shift_id, 0) = IFNULL(NEW.shift_id, 0)
)
BEGIN
    SELECT RAISE(ABORT, 'The scheduled activity does not apply to this worker on this date');
END;

-- A replacement must follow a voided record of the same worker, date and expected activity.
CREATE TRIGGER trg_work_record_replacement_consistent
BEFORE INSERT ON work_record
WHEN NEW.replaces_record_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM work_record o
    WHERE o.id = NEW.replaces_record_id
      AND o.voided_at IS NOT NULL
      AND o.worker_id = NEW.worker_id
      AND o.work_date = NEW.work_date
      AND IFNULL(o.schedule_entry_id, 0) = IFNULL(NEW.schedule_entry_id, 0)
)
BEGIN
    SELECT RAISE(ABORT, 'A correction must replace a voided record of the same worker, date and activity');
END;

-- Recorded facts never change. Only voiding (once) and a pending deduction decision may.
CREATE TRIGGER trg_work_record_immutable
BEFORE UPDATE ON work_record
WHEN NEW.id IS NOT OLD.id
  OR NEW.worker_id IS NOT OLD.worker_id
  OR NEW.work_date IS NOT OLD.work_date
  OR NEW.schedule_entry_id IS NOT OLD.schedule_entry_id
  OR NEW.task_id IS NOT OLD.task_id
  OR NEW.shift_id IS NOT OLD.shift_id
  OR NEW.outcome IS NOT OLD.outcome
  OR NEW.absence_reason IS NOT OLD.absence_reason
  OR NEW.expected_minutes IS NOT OLD.expected_minutes
  OR NEW.expected_quantity_milli IS NOT OLD.expected_quantity_milli
  OR NEW.actual_start IS NOT OLD.actual_start
  OR NEW.actual_end IS NOT OLD.actual_end
  OR NEW.actual_minutes IS NOT OLD.actual_minutes
  OR NEW.actual_quantity_milli IS NOT OLD.actual_quantity_milli
  OR NEW.note IS NOT OLD.note
  OR NEW.recorded_at IS NOT OLD.recorded_at
  OR NEW.replaces_record_id IS NOT OLD.replaces_record_id
  OR (OLD.voided_at IS NOT NULL AND (NEW.voided_at IS NOT OLD.voided_at OR NEW.void_reason IS NOT OLD.void_reason))
BEGIN
    SELECT RAISE(ABORT, 'Work records cannot be changed; void or correct them instead');
END;

-- A deduction decision can only move once, from PENDING to APPROVED or WAIVED, on a current record.
CREATE TRIGGER trg_work_record_deduction_decision
BEFORE UPDATE OF deduction_decision ON work_record
WHEN NEW.deduction_decision IS NOT OLD.deduction_decision
 AND (OLD.deduction_decision IS NOT 'PENDING'
      OR NEW.deduction_decision NOT IN ('APPROVED', 'WAIVED')
      OR OLD.voided_at IS NOT NULL)
BEGIN
    SELECT RAISE(ABORT, 'A deduction decision can only be made once, on a current pending unauthorized absence');
END;

CREATE TRIGGER trg_work_record_no_delete
BEFORE DELETE ON work_record
BEGIN
    SELECT RAISE(ABORT, 'Work records cannot be deleted; void them instead');
END;

-- Protect recorded work from later schedule and employment changes (tables from V2/V4).

-- A schedule version cannot be shortened so that current records on its entries fall outside it.
CREATE TRIGGER trg_work_schedule_protect_work_records
BEFORE UPDATE OF effective_from, effective_to ON work_schedule
WHEN EXISTS (
    SELECT 1 FROM work_record r
    JOIN schedule_entry e ON e.id = r.schedule_entry_id
    WHERE e.schedule_id = NEW.id
      AND r.voided_at IS NULL
      AND (r.work_date < NEW.effective_from OR (NEW.effective_to IS NOT NULL AND r.work_date > NEW.effective_to))
)
BEGIN
    SELECT RAISE(ABORT, 'Work has been recorded against this schedule on dates the change would remove');
END;

-- An employment period cannot be shortened so that current work records fall outside it.
CREATE TRIGGER trg_employment_period_protect_work_records
BEFORE UPDATE OF start_date, end_date ON employment_period
WHEN EXISTS (
    SELECT 1 FROM work_record r
    WHERE r.worker_id = OLD.worker_id
      AND r.voided_at IS NULL
      AND r.work_date >= OLD.start_date
      AND (OLD.end_date IS NULL OR r.work_date <= OLD.end_date)
      AND (r.work_date < NEW.start_date OR (NEW.end_date IS NOT NULL AND r.work_date > NEW.end_date))
)
BEGIN
    SELECT RAISE(ABORT, 'Work has been recorded on dates this employment change would remove');
END;
