-- Worker types, workers and employment periods.
--
-- Conventions used by all Phase 2 migrations:
--   * Dates are ISO-8601 TEXT 'YYYY-MM-DD'; CHECK (date(x) IS x) rejects anything else.
--   * A NULL end date means the period is still open.
--   * Date ranges are inclusive on both ends.
--   * Booleans are INTEGER 0/1.

CREATE TABLE worker_type (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    name     TEXT    NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(name)) BETWEEN 1 AND 50),
    built_in INTEGER NOT NULL DEFAULT 0 CHECK (built_in IN (0, 1))
);

INSERT INTO worker_type (name, built_in) VALUES
    ('Maid', 1),
    ('Cook', 1),
    ('Milkman', 1),
    ('Driver', 1),
    ('Cleaner', 1),
    ('Gardener', 1);

CREATE TABLE worker (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT    NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 100),
    worker_type_id INTEGER NOT NULL REFERENCES worker_type (id),
    phone          TEXT,
    notes          TEXT,
    created_at     TEXT    NOT NULL
);

CREATE INDEX idx_worker_worker_type ON worker (worker_type_id);

CREATE TABLE employment_period (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id  INTEGER NOT NULL REFERENCES worker (id),
    start_date TEXT    NOT NULL CHECK (date(start_date) IS start_date),
    end_date   TEXT             CHECK (date(end_date) IS end_date),
    end_reason TEXT,
    CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_employment_period_worker ON employment_period (worker_id, start_date);

-- At most one open (current) employment period per worker.
CREATE UNIQUE INDEX uq_employment_period_one_open ON employment_period (worker_id) WHERE end_date IS NULL;

-- Employment periods of the same worker must never overlap.
CREATE TRIGGER trg_employment_period_no_overlap_insert
BEFORE INSERT ON employment_period
WHEN EXISTS (
    SELECT 1 FROM employment_period p
    WHERE p.worker_id = NEW.worker_id
      AND p.start_date <= COALESCE(NEW.end_date, '9999-12-31')
      AND NEW.start_date <= COALESCE(p.end_date, '9999-12-31')
)
BEGIN
    SELECT RAISE(ABORT, 'Employment periods for the same worker must not overlap');
END;

CREATE TRIGGER trg_employment_period_no_overlap_update
BEFORE UPDATE OF worker_id, start_date, end_date ON employment_period
WHEN EXISTS (
    SELECT 1 FROM employment_period p
    WHERE p.worker_id = NEW.worker_id
      AND p.id <> NEW.id
      AND p.start_date <= COALESCE(NEW.end_date, '9999-12-31')
      AND NEW.start_date <= COALESCE(p.end_date, '9999-12-31')
)
BEGIN
    SELECT RAISE(ABORT, 'Employment periods for the same worker must not overlap');
END;
