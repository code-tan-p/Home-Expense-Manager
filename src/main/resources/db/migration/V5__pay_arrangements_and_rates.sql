-- Effective-dated payment arrangements and pay rates.
--
-- pay_arrangement: which ONE primary payment model applies to a worker on a date.
-- pay_rate: an amount for one rate type, optionally specific to a task or a shift.
--
-- Rate selection for (worker, date, task, shift) is unambiguous because ranges of the same
-- (worker, rate_type, task, shift) key never overlap: the task/shift-specific rate wins,
-- otherwise the standard rate (no task, no shift) applies. Different tasks or shifts can
-- have their own rates at the same time.
--
-- Money is INTEGER paise (1 rupee = 100 paise). typeof() = 'integer' rejects REAL values.

CREATE TABLE pay_arrangement (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id      INTEGER NOT NULL REFERENCES worker (id),
    primary_model  TEXT    NOT NULL CHECK (primary_model IN
                       ('MONTHLY_SALARY', 'PER_TASK', 'HOURLY', 'PER_UNIT', 'PER_DAY', 'PER_SHIFT')),
    effective_from TEXT    NOT NULL CHECK (date(effective_from) IS effective_from),
    effective_to   TEXT             CHECK (date(effective_to) IS effective_to),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX idx_pay_arrangement_worker ON pay_arrangement (worker_id, effective_from);

CREATE TRIGGER trg_pay_arrangement_no_overlap_insert
BEFORE INSERT ON pay_arrangement
WHEN EXISTS (
    SELECT 1 FROM pay_arrangement a
    WHERE a.worker_id = NEW.worker_id
      AND a.effective_from <= COALESCE(NEW.effective_to, '9999-12-31')
      AND NEW.effective_from <= COALESCE(a.effective_to, '9999-12-31')
)
BEGIN
    SELECT RAISE(ABORT, 'Payment arrangements for the same worker must not overlap');
END;

CREATE TRIGGER trg_pay_arrangement_no_overlap_update
BEFORE UPDATE OF worker_id, effective_from, effective_to ON pay_arrangement
WHEN EXISTS (
    SELECT 1 FROM pay_arrangement a
    WHERE a.worker_id = NEW.worker_id
      AND a.id <> NEW.id
      AND a.effective_from <= COALESCE(NEW.effective_to, '9999-12-31')
      AND NEW.effective_from <= COALESCE(a.effective_to, '9999-12-31')
)
BEGIN
    SELECT RAISE(ABORT, 'Payment arrangements for the same worker must not overlap');
END;

CREATE TABLE pay_rate (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id      INTEGER NOT NULL REFERENCES worker (id),
    rate_type      TEXT    NOT NULL CHECK (rate_type IN
                       ('MONTHLY_SALARY', 'PER_TASK', 'HOURLY', 'PER_UNIT', 'PER_DAY', 'PER_SHIFT',
                        'EXTRA_TASK', 'EXTRA_HOUR', 'EXTRA_DAY')),
    task_id        INTEGER REFERENCES task (id),
    shift_id       INTEGER REFERENCES shift (id),
    unit_label     TEXT    CHECK (unit_label IS NULL OR length(trim(unit_label)) BETWEEN 1 AND 20),
    amount_paise   INTEGER NOT NULL CHECK (typeof(amount_paise) = 'integer' AND amount_paise > 0),
    effective_from TEXT    NOT NULL CHECK (date(effective_from) IS effective_from),
    effective_to   TEXT             CHECK (date(effective_to) IS effective_to),
    note           TEXT,
    -- Only per-task, per-unit and extra-task rates may name a task; extra-task must.
    CHECK (task_id IS NULL OR rate_type IN ('PER_TASK', 'PER_UNIT', 'EXTRA_TASK')),
    CHECK (rate_type <> 'EXTRA_TASK' OR task_id IS NOT NULL),
    -- Only per-shift rates may name a shift.
    CHECK (shift_id IS NULL OR rate_type = 'PER_SHIFT'),
    -- Per-unit rates need a unit (litre, kg, ...); other rates must not have one.
    CHECK ((rate_type = 'PER_UNIT') = (unit_label IS NOT NULL)),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX idx_pay_rate_worker ON pay_rate (worker_id, rate_type, effective_from);

CREATE TRIGGER trg_pay_rate_no_overlap_insert
BEFORE INSERT ON pay_rate
WHEN EXISTS (
    SELECT 1 FROM pay_rate r
    WHERE r.worker_id = NEW.worker_id
      AND r.rate_type = NEW.rate_type
      AND IFNULL(r.task_id, 0) = IFNULL(NEW.task_id, 0)
      AND IFNULL(r.shift_id, 0) = IFNULL(NEW.shift_id, 0)
      AND r.effective_from <= COALESCE(NEW.effective_to, '9999-12-31')
      AND NEW.effective_from <= COALESCE(r.effective_to, '9999-12-31')
)
BEGIN
    SELECT RAISE(ABORT, 'Pay rates for the same worker, rate type, task and shift must not overlap');
END;

CREATE TRIGGER trg_pay_rate_no_overlap_update
BEFORE UPDATE OF worker_id, rate_type, task_id, shift_id, effective_from, effective_to ON pay_rate
WHEN EXISTS (
    SELECT 1 FROM pay_rate r
    WHERE r.worker_id = NEW.worker_id
      AND r.id <> NEW.id
      AND r.rate_type = NEW.rate_type
      AND IFNULL(r.task_id, 0) = IFNULL(NEW.task_id, 0)
      AND IFNULL(r.shift_id, 0) = IFNULL(NEW.shift_id, 0)
      AND r.effective_from <= COALESCE(NEW.effective_to, '9999-12-31')
      AND NEW.effective_from <= COALESCE(r.effective_to, '9999-12-31')
)
BEGIN
    SELECT RAISE(ABORT, 'Pay rates for the same worker, rate type, task and shift must not overlap');
END;
