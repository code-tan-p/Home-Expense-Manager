-- Effective-dated weekly work schedules.
--
-- Each work_schedule row is one version of a worker's weekly pattern, valid from
-- effective_from to effective_to (inclusive; NULL = open-ended). Versions of the same
-- worker never overlap. A change adds a new version; earlier versions keep their entries.

CREATE TABLE work_schedule (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id      INTEGER NOT NULL REFERENCES worker (id),
    effective_from TEXT    NOT NULL CHECK (date(effective_from) IS effective_from),
    effective_to   TEXT             CHECK (date(effective_to) IS effective_to),
    note           TEXT,
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX idx_work_schedule_worker ON work_schedule (worker_id, effective_from);

CREATE TRIGGER trg_work_schedule_no_overlap_insert
BEFORE INSERT ON work_schedule
WHEN EXISTS (
    SELECT 1 FROM work_schedule s
    WHERE s.worker_id = NEW.worker_id
      AND s.effective_from <= COALESCE(NEW.effective_to, '9999-12-31')
      AND NEW.effective_from <= COALESCE(s.effective_to, '9999-12-31')
)
BEGIN
    SELECT RAISE(ABORT, 'Schedule versions for the same worker must not overlap');
END;

CREATE TRIGGER trg_work_schedule_no_overlap_update
BEFORE UPDATE OF worker_id, effective_from, effective_to ON work_schedule
WHEN EXISTS (
    SELECT 1 FROM work_schedule s
    WHERE s.worker_id = NEW.worker_id
      AND s.id <> NEW.id
      AND s.effective_from <= COALESCE(NEW.effective_to, '9999-12-31')
      AND NEW.effective_from <= COALESCE(s.effective_to, '9999-12-31')
)
BEGIN
    SELECT RAISE(ABORT, 'Schedule versions for the same worker must not overlap');
END;

-- One expected activity in the weekly pattern. day_of_week is ISO: 1 = Monday ... 7 = Sunday.
-- start_time/end_time override the shift's default times; duration_minutes is only for
-- untimed entries. expected_quantity_milli is in thousandths of a unit (1.5 litres = 1500).
CREATE TABLE schedule_entry (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    schedule_id             INTEGER NOT NULL REFERENCES work_schedule (id),
    day_of_week             INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    task_id                 INTEGER REFERENCES task (id),
    shift_id                INTEGER REFERENCES shift (id),
    start_time              TEXT CHECK (strftime('%H:%M', start_time) IS start_time AND start_time < '24:00'),
    end_time                TEXT CHECK (strftime('%H:%M', end_time) IS end_time AND end_time < '24:00'),
    duration_minutes        INTEGER CHECK (duration_minutes IS NULL OR duration_minutes BETWEEN 1 AND 1440),
    expected_quantity_milli INTEGER CHECK (expected_quantity_milli IS NULL OR expected_quantity_milli > 0),
    CHECK (task_id IS NOT NULL OR shift_id IS NOT NULL),
    CHECK ((start_time IS NULL) = (end_time IS NULL)),
    CHECK (start_time IS NULL OR end_time > start_time),
    CHECK (start_time IS NULL OR duration_minutes IS NULL)
);

CREATE INDEX idx_schedule_entry_schedule ON schedule_entry (schedule_id, day_of_week);

-- Several tasks or shifts on the same day are allowed; an identical entry twice is not.
CREATE UNIQUE INDEX uq_schedule_entry_identity ON schedule_entry (
    schedule_id, day_of_week, IFNULL(task_id, 0), IFNULL(shift_id, 0), IFNULL(start_time, '')
);
