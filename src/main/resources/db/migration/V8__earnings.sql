-- Earnings calculation (Phase 4).
--
-- An earning_run is the calculation for one worker and one calendar month. While it is a
-- DRAFT its lines are derived data and are replaced on every recalculation. Once FINALIZED
-- the run, its lines and its record treatments can no longer change, and work records,
-- rates and other inputs for those dates are locked (here and in the services).
--
-- Money is INTEGER paise. Each line is calculated as an exact fraction of paise
-- (exact_paise, e.g. '1700000/3') and rounded HALF_UP once to amount_paise. Lines are never
-- negative: unpaid time reduces salary day-units instead of creating deduction lines.
-- Reopening finalized earnings belongs to settlements (Phase 5).
--
-- CHECK constraints pass when they evaluate to NULL, so checks on nullable columns use IS.

CREATE TABLE earning_run (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id          INTEGER NOT NULL REFERENCES worker (id),
    period_start       TEXT    NOT NULL CHECK (date(period_start) IS period_start AND strftime('%d', period_start) = '01'),
    period_end         TEXT    NOT NULL,
    status             TEXT    NOT NULL CHECK (status IN ('DRAFT', 'FINALIZED')),
    engine_version     INTEGER NOT NULL CHECK (engine_version > 0),
    calculated_at      TEXT    NOT NULL,
    finalized_at       TEXT,
    total_paise        INTEGER NOT NULL CHECK (typeof(total_paise) = 'integer' AND total_paise >= 0),
    needs_review_count INTEGER NOT NULL CHECK (needs_review_count >= 0),
    blocker_count      INTEGER NOT NULL CHECK (blocker_count >= 0),
    CHECK (period_end IS date(period_start, '+1 month', '-1 day')),
    CHECK ((status = 'FINALIZED') = (finalized_at IS NOT NULL)),
    CHECK (status <> 'FINALIZED' OR (needs_review_count = 0 AND blocker_count = 0))
);

-- One calculation per worker and month.
CREATE UNIQUE INDEX uq_earning_run_worker_month ON earning_run (worker_id, period_start);

CREATE TABLE earning_line (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id                 INTEGER NOT NULL REFERENCES earning_run (id),
    line_no                INTEGER NOT NULL CHECK (line_no > 0),
    line_type              TEXT    NOT NULL CHECK (line_type IN
                               ('MONTHLY_SALARY', 'PER_TASK', 'HOURLY', 'PER_UNIT', 'PER_DAY', 'PER_SHIFT',
                                'EXTRA_TASK', 'EXTRA_HOUR', 'EXTRA_DAY')),
    basis                  TEXT    NOT NULL CHECK (basis IN ('SALARY', 'WORK', 'HOLIDAY', 'EXTRA_WORK')),
    work_date              TEXT    CHECK (date(work_date) IS work_date),
    -- Set when exactly one work record is the source; day and salary lines use treatments.
    work_record_id         INTEGER REFERENCES work_record (id),
    pay_rate_id            INTEGER REFERENCES pay_rate (id),
    rate_amount_paise      INTEGER CHECK (rate_amount_paise IS NULL OR (typeof(rate_amount_paise) = 'integer' AND rate_amount_paise > 0)),
    unit_label             TEXT,
    quantity_milli         INTEGER CHECK (quantity_milli IS NULL OR quantity_milli > 0),
    minutes                INTEGER CHECK (minutes IS NULL OR minutes > 0),
    paid_day_units         TEXT,
    scheduled_day_units    INTEGER CHECK (scheduled_day_units IS NULL OR scheduled_day_units > 0),
    exact_paise            TEXT,
    suggested_amount_paise INTEGER CHECK (suggested_amount_paise IS NULL OR (typeof(suggested_amount_paise) = 'integer' AND suggested_amount_paise >= 0)),
    amount_paise           INTEGER CHECK (amount_paise IS NULL OR (typeof(amount_paise) = 'integer' AND amount_paise >= 0)),
    review_status          TEXT    NOT NULL CHECK (review_status IN ('NONE', 'NEEDS_REVIEW', 'ACCEPTED', 'OVERRIDDEN')),
    review_key             TEXT,
    explanation            TEXT    NOT NULL CHECK (length(trim(explanation)) > 0),
    UNIQUE (run_id, line_no),
    -- A line without an amount is waiting for review, and only such a line.
    CHECK ((review_status = 'NEEDS_REVIEW') = (amount_paise IS NULL)),
    CHECK ((review_status = 'NONE') = (review_key IS NULL)),
    CHECK (line_type <> 'MONTHLY_SALARY' OR (basis = 'SALARY' AND paid_day_units IS NOT NULL AND scheduled_day_units IS NOT NULL)),
    CHECK (line_type = 'MONTHLY_SALARY' OR work_date IS NOT NULL)
);

CREATE INDEX idx_earning_line_run ON earning_line (run_id);
CREATE INDEX idx_earning_line_rate ON earning_line (pay_rate_id);

-- A work record is the single source of at most one line per run.
CREATE UNIQUE INDEX uq_earning_line_record ON earning_line (run_id, work_record_id) WHERE work_record_id IS NOT NULL;

-- At most one day-type line (day rate or extra-day rate) per date per run.
CREATE UNIQUE INDEX uq_earning_line_day ON earning_line (run_id, work_date) WHERE line_type IN ('PER_DAY', 'EXTRA_DAY');

-- How each current work record was treated. Exactly one treatment per record per run.
CREATE TABLE earning_record_treatment (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id          INTEGER NOT NULL REFERENCES earning_run (id),
    work_record_id  INTEGER NOT NULL REFERENCES work_record (id),
    treatment       TEXT    NOT NULL CHECK (treatment IN
                        ('PAID', 'COVERED_BY_SALARY', 'COVERED_BY_DAY_RATE', 'COVERED_BY_EXTRA_DAY',
                         'HOLIDAY_PAID', 'HOLIDAY_NOT_PAID', 'UNPAID_LEAVE', 'ABSENCE_UNPAID',
                         'ABSENCE_PENDING_PAID', 'ABSENCE_WAIVED_PAID', 'ABSENCE_APPROVED_UNPAID',
                         'SALARY_PARTIAL_REDUCTION', 'NEEDS_REVIEW', 'NO_APPLICABLE_RATE')),
    line_id         INTEGER REFERENCES earning_line (id),
    review_key      TEXT,
    review_status   TEXT    NOT NULL CHECK (review_status IN ('NONE', 'NEEDS_REVIEW', 'ACCEPTED', 'OVERRIDDEN')),
    suggested_value TEXT,
    max_value       TEXT,
    applied_value   TEXT,
    detail          TEXT    NOT NULL CHECK (length(trim(detail)) > 0),
    UNIQUE (run_id, work_record_id),
    CHECK ((review_status = 'NONE') = (review_key IS NULL))
);

CREATE INDEX idx_earning_treatment_record ON earning_record_treatment (work_record_id);

-- Warnings and blockers shown with a run.
CREATE TABLE earning_run_note (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id  INTEGER NOT NULL REFERENCES earning_run (id),
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    kind    TEXT    NOT NULL CHECK (kind IN ('WARNING', 'BLOCKER')),
    message TEXT    NOT NULL CHECK (length(trim(message)) > 0),
    UNIQUE (run_id, ordinal)
);

-- The household's review decisions. They survive recalculation but only apply while the
-- review key (which names the exact work records) and the suggestion are unchanged.
CREATE TABLE earning_review (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id           INTEGER NOT NULL REFERENCES worker (id),
    review_key          TEXT    NOT NULL CHECK (length(review_key) > 0),
    decision            TEXT    NOT NULL CHECK (decision IN ('ACCEPT_SUGGESTED', 'OVERRIDE')),
    amount_paise        INTEGER CHECK (amount_paise IS NULL OR (typeof(amount_paise) = 'integer' AND amount_paise >= 0)),
    unpaid_day_units    TEXT,
    suggestion_snapshot TEXT    NOT NULL,
    reason              TEXT,
    decided_at          TEXT    NOT NULL,
    superseded_at       TEXT,
    CHECK (decision <> 'OVERRIDE' OR ((amount_paise IS NULL) <> (unpaid_day_units IS NULL)
                                      AND length(trim(IFNULL(reason, ''))) > 0)),
    CHECK (decision <> 'ACCEPT_SUGGESTED' OR (amount_paise IS NULL AND unpaid_day_units IS NULL))
);

CREATE UNIQUE INDEX uq_earning_review_current ON earning_review (worker_id, review_key) WHERE superseded_at IS NULL;

-- Explicit confirmations about unrecorded scheduled activities. Valid only while the
-- fingerprint matches the current set of unrecorded dates and activities.
CREATE TABLE earning_unrecorded_confirmation (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id     INTEGER NOT NULL REFERENCES worker (id),
    period_start  TEXT    NOT NULL CHECK (date(period_start) IS period_start AND strftime('%d', period_start) = '01'),
    items         TEXT    NOT NULL CHECK (length(items) > 0),
    fingerprint   TEXT    NOT NULL CHECK (length(fingerprint) > 0),
    statement     TEXT    NOT NULL CHECK (length(trim(statement)) > 0),
    confirmed_at  TEXT    NOT NULL,
    superseded_at TEXT
);

CREATE UNIQUE INDEX uq_unrecorded_confirmation_current
    ON earning_unrecorded_confirmation (worker_id, period_start) WHERE superseded_at IS NULL;

-- ---------------------------------------------------------------- finalization protection

-- Lines, treatments and notes can only change while their run is a draft.
CREATE TRIGGER trg_earning_line_draft_only_insert BEFORE INSERT ON earning_line
WHEN (SELECT status FROM earning_run WHERE id = NEW.run_id) IS NOT 'DRAFT'
BEGIN SELECT RAISE(ABORT, 'Earnings can only be changed while the calculation is a draft'); END;

CREATE TRIGGER trg_earning_line_draft_only_update BEFORE UPDATE ON earning_line
WHEN (SELECT status FROM earning_run WHERE id = OLD.run_id) IS NOT 'DRAFT'
BEGIN SELECT RAISE(ABORT, 'Earnings can only be changed while the calculation is a draft'); END;

CREATE TRIGGER trg_earning_line_draft_only_delete BEFORE DELETE ON earning_line
WHEN (SELECT status FROM earning_run WHERE id = OLD.run_id) IS NOT 'DRAFT'
BEGIN SELECT RAISE(ABORT, 'Earnings can only be changed while the calculation is a draft'); END;

CREATE TRIGGER trg_earning_treatment_draft_only_insert BEFORE INSERT ON earning_record_treatment
WHEN (SELECT status FROM earning_run WHERE id = NEW.run_id) IS NOT 'DRAFT'
BEGIN SELECT RAISE(ABORT, 'Earnings can only be changed while the calculation is a draft'); END;

CREATE TRIGGER trg_earning_treatment_draft_only_update BEFORE UPDATE ON earning_record_treatment
WHEN (SELECT status FROM earning_run WHERE id = OLD.run_id) IS NOT 'DRAFT'
BEGIN SELECT RAISE(ABORT, 'Earnings can only be changed while the calculation is a draft'); END;

CREATE TRIGGER trg_earning_treatment_draft_only_delete BEFORE DELETE ON earning_record_treatment
WHEN (SELECT status FROM earning_run WHERE id = OLD.run_id) IS NOT 'DRAFT'
BEGIN SELECT RAISE(ABORT, 'Earnings can only be changed while the calculation is a draft'); END;

CREATE TRIGGER trg_earning_note_draft_only_insert BEFORE INSERT ON earning_run_note
WHEN (SELECT status FROM earning_run WHERE id = NEW.run_id) IS NOT 'DRAFT'
BEGIN SELECT RAISE(ABORT, 'Earnings can only be changed while the calculation is a draft'); END;

CREATE TRIGGER trg_earning_note_draft_only_delete BEFORE DELETE ON earning_run_note
WHEN (SELECT status FROM earning_run WHERE id = OLD.run_id) IS NOT 'DRAFT'
BEGIN SELECT RAISE(ABORT, 'Earnings can only be changed while the calculation is a draft'); END;

-- A finalized run never changes and is never deleted (reopening is a Phase 5 settlement feature).
CREATE TRIGGER trg_earning_run_finalized_immutable BEFORE UPDATE ON earning_run
WHEN OLD.status = 'FINALIZED'
BEGIN SELECT RAISE(ABORT, 'Finalized earnings cannot be changed'); END;

CREATE TRIGGER trg_earning_run_no_delete BEFORE DELETE ON earning_run
BEGIN SELECT RAISE(ABORT, 'Earning calculations cannot be deleted'); END;

-- Review decisions and confirmations: history is kept; only superseding (once) is allowed.
CREATE TRIGGER trg_earning_review_immutable BEFORE UPDATE ON earning_review
WHEN OLD.superseded_at IS NOT NULL
  OR NEW.worker_id IS NOT OLD.worker_id OR NEW.review_key IS NOT OLD.review_key
  OR NEW.decision IS NOT OLD.decision OR NEW.amount_paise IS NOT OLD.amount_paise
  OR NEW.unpaid_day_units IS NOT OLD.unpaid_day_units OR NEW.suggestion_snapshot IS NOT OLD.suggestion_snapshot
  OR NEW.reason IS NOT OLD.reason OR NEW.decided_at IS NOT OLD.decided_at
BEGIN SELECT RAISE(ABORT, 'Review decisions cannot be changed; record a new decision instead'); END;

CREATE TRIGGER trg_earning_review_no_delete BEFORE DELETE ON earning_review
BEGIN SELECT RAISE(ABORT, 'Review decisions cannot be deleted'); END;

CREATE TRIGGER trg_unrecorded_confirmation_immutable BEFORE UPDATE ON earning_unrecorded_confirmation
WHEN OLD.superseded_at IS NOT NULL
  OR NEW.worker_id IS NOT OLD.worker_id OR NEW.period_start IS NOT OLD.period_start
  OR NEW.items IS NOT OLD.items OR NEW.fingerprint IS NOT OLD.fingerprint
  OR NEW.statement IS NOT OLD.statement OR NEW.confirmed_at IS NOT OLD.confirmed_at
BEGIN SELECT RAISE(ABORT, 'Confirmations cannot be changed; record a new confirmation instead'); END;

CREATE TRIGGER trg_unrecorded_confirmation_no_delete BEFORE DELETE ON earning_unrecorded_confirmation
BEGIN SELECT RAISE(ABORT, 'Confirmations cannot be deleted'); END;

-- Backstops for the service checks: finalized dates lock their work records and rates.
CREATE TRIGGER trg_work_record_finalized_insert BEFORE INSERT ON work_record
WHEN EXISTS (SELECT 1 FROM earning_run r WHERE r.worker_id = NEW.worker_id AND r.status = 'FINALIZED'
             AND NEW.work_date BETWEEN r.period_start AND r.period_end)
BEGIN SELECT RAISE(ABORT, 'Earnings for this date are finalized; work cannot be recorded'); END;

CREATE TRIGGER trg_work_record_finalized_update BEFORE UPDATE ON work_record
WHEN EXISTS (SELECT 1 FROM earning_run r WHERE r.worker_id = OLD.worker_id AND r.status = 'FINALIZED'
             AND OLD.work_date BETWEEN r.period_start AND r.period_end)
BEGIN SELECT RAISE(ABORT, 'Earnings for this date are finalized; the work record cannot change'); END;

CREATE TRIGGER trg_pay_rate_finalized_update BEFORE UPDATE OF amount_paise, effective_from, effective_to ON pay_rate
WHEN EXISTS (SELECT 1 FROM earning_line l JOIN earning_run r ON r.id = l.run_id
             WHERE l.pay_rate_id = OLD.id AND r.status = 'FINALIZED'
               AND (NEW.amount_paise IS NOT OLD.amount_paise
                    OR l.work_date < NEW.effective_from
                    OR (NEW.effective_to IS NOT NULL AND (l.work_date > NEW.effective_to
                        OR (l.work_date IS NULL AND r.period_start > NEW.effective_to)))))
BEGIN SELECT RAISE(ABORT, 'This rate is used by finalized earnings and cannot change for those dates'); END;
