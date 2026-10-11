-- Payments, allocations, advances, settlements, adjustments and reopening (Phase 5).
--
-- Principles:
--   * Money is INTEGER paise; typeof() = 'integer' rejects REAL values.
--   * Financial history is never updated or deleted. Corrections are new, linked records:
--     payment voids, allocation reversals, recovery reversals and reversal adjustments.
--   * A settlement is the stable identity of one worker-month; allocations and recoveries
--     point at it. Each settlement_version is a snapshot tied to one finalized earnings run.
--   * Finalized earnings are never changed. Reopening records a supersession; the old run
--     stays exactly as it was and only the active (non-superseded) run locks its dates.
--
-- This migration changes V8 objects without editing V8: it drops the one-run-per-month
-- unique index (replaced by a one-active-run trigger) and re-creates the three V8 lock
-- triggers so that they ignore superseded runs. No table is rebuilt, so foreign keys stay
-- enforced throughout and Flyway applies everything in one transaction.
--
-- CHECK constraints pass when they evaluate to NULL, so checks on nullable values use IS/IFNULL.

-- ===================================================================== earnings supersession

CREATE TABLE earning_run_supersession (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    superseded_run_id  INTEGER NOT NULL UNIQUE REFERENCES earning_run (id),
    replacement_run_id INTEGER UNIQUE REFERENCES earning_run (id),
    reason             TEXT    NOT NULL CHECK (length(trim(reason)) > 0),
    reopened_at        TEXT    NOT NULL,
    CHECK (replacement_run_id IS NULL OR replacement_run_id <> superseded_run_id)
);

CREATE TRIGGER trg_supersession_requires_finalized BEFORE INSERT ON earning_run_supersession
WHEN (SELECT status FROM earning_run WHERE id = NEW.superseded_run_id) IS NOT 'FINALIZED'
BEGIN SELECT RAISE(ABORT, 'Only finalized earnings can be reopened'); END;

-- Only the replacement link may be set, once, to a run of the same worker and month.
CREATE TRIGGER trg_supersession_immutable BEFORE UPDATE ON earning_run_supersession
WHEN OLD.replacement_run_id IS NOT NULL
  OR NEW.superseded_run_id IS NOT OLD.superseded_run_id
  OR NEW.reason IS NOT OLD.reason
  OR NEW.reopened_at IS NOT OLD.reopened_at
  OR NOT EXISTS (SELECT 1 FROM earning_run o JOIN earning_run n ON n.id = NEW.replacement_run_id
                 WHERE o.id = NEW.superseded_run_id AND o.worker_id = n.worker_id AND o.period_start = n.period_start)
BEGIN SELECT RAISE(ABORT, 'A reopening record cannot be changed'); END;

CREATE TRIGGER trg_supersession_no_delete BEFORE DELETE ON earning_run_supersession
BEGIN SELECT RAISE(ABORT, 'Reopening records cannot be deleted'); END;

-- One active run per worker and month (replaces V8's one-run-ever unique index).
DROP INDEX uq_earning_run_worker_month;
CREATE INDEX idx_earning_run_worker_month ON earning_run (worker_id, period_start);

CREATE TRIGGER trg_earning_run_one_active BEFORE INSERT ON earning_run
WHEN EXISTS (SELECT 1 FROM earning_run r
             WHERE r.worker_id = NEW.worker_id AND r.period_start = NEW.period_start
               AND NOT EXISTS (SELECT 1 FROM earning_run_supersession s WHERE s.superseded_run_id = r.id))
BEGIN SELECT RAISE(ABORT, 'Only one active earnings calculation is allowed per worker and month'); END;

-- V8 locks, re-created so that superseded (reopened) runs no longer lock their dates.
DROP TRIGGER trg_work_record_finalized_insert;
CREATE TRIGGER trg_work_record_finalized_insert BEFORE INSERT ON work_record
WHEN EXISTS (SELECT 1 FROM earning_run r WHERE r.worker_id = NEW.worker_id AND r.status = 'FINALIZED'
             AND NEW.work_date BETWEEN r.period_start AND r.period_end
             AND NOT EXISTS (SELECT 1 FROM earning_run_supersession s WHERE s.superseded_run_id = r.id))
BEGIN SELECT RAISE(ABORT, 'Earnings for this date are finalized; work cannot be recorded'); END;

DROP TRIGGER trg_work_record_finalized_update;
CREATE TRIGGER trg_work_record_finalized_update BEFORE UPDATE ON work_record
WHEN EXISTS (SELECT 1 FROM earning_run r WHERE r.worker_id = OLD.worker_id AND r.status = 'FINALIZED'
             AND OLD.work_date BETWEEN r.period_start AND r.period_end
             AND NOT EXISTS (SELECT 1 FROM earning_run_supersession s WHERE s.superseded_run_id = r.id))
BEGIN SELECT RAISE(ABORT, 'Earnings for this date are finalized; the work record cannot change'); END;

DROP TRIGGER trg_pay_rate_finalized_update;
CREATE TRIGGER trg_pay_rate_finalized_update BEFORE UPDATE OF amount_paise, effective_from, effective_to ON pay_rate
WHEN EXISTS (SELECT 1 FROM earning_line l JOIN earning_run r ON r.id = l.run_id
             WHERE l.pay_rate_id = OLD.id AND r.status = 'FINALIZED'
               AND NOT EXISTS (SELECT 1 FROM earning_run_supersession s WHERE s.superseded_run_id = r.id)
               AND (NEW.amount_paise IS NOT OLD.amount_paise
                    OR l.work_date < NEW.effective_from
                    OR (NEW.effective_to IS NOT NULL AND (l.work_date > NEW.effective_to
                        OR (l.work_date IS NULL AND r.period_start > NEW.effective_to)))))
BEGIN SELECT RAISE(ABORT, 'This rate is used by finalized earnings and cannot change for those dates'); END;

-- ===================================================================== settlements

CREATE TABLE settlement (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id    INTEGER NOT NULL REFERENCES worker (id),
    period_start TEXT    NOT NULL CHECK (date(period_start) IS period_start AND strftime('%d', period_start) = '01'),
    created_at   TEXT    NOT NULL,
    UNIQUE (worker_id, period_start)
);

CREATE TRIGGER trg_settlement_immutable BEFORE UPDATE ON settlement
BEGIN SELECT RAISE(ABORT, 'Settlements cannot be changed'); END;

CREATE TRIGGER trg_settlement_no_delete BEFORE DELETE ON settlement
BEGIN SELECT RAISE(ABORT, 'Settlements cannot be deleted'); END;

-- net = earnings + negative adjustments + neutralised shortfall + positive adjustments - recovery + rounding
CREATE TABLE settlement_version (
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    settlement_id               INTEGER NOT NULL REFERENCES settlement (id),
    version_no                  INTEGER NOT NULL CHECK (version_no > 0),
    earning_run_id              INTEGER NOT NULL REFERENCES earning_run (id),
    status                      TEXT    NOT NULL CHECK (status IN ('OPEN', 'SETTLED', 'SUPERSEDED')),
    rounding_mode               TEXT    NOT NULL CHECK (rounding_mode IN ('EXACT', 'UP', 'DOWN', 'NEAREST')),
    earnings_paise              INTEGER NOT NULL CHECK (typeof(earnings_paise) = 'integer' AND earnings_paise >= 0),
    negative_adjustments_paise  INTEGER NOT NULL CHECK (typeof(negative_adjustments_paise) = 'integer' AND negative_adjustments_paise <= 0),
    shortfall_paise             INTEGER NOT NULL CHECK (typeof(shortfall_paise) = 'integer' AND shortfall_paise >= 0),
    positive_adjustments_paise  INTEGER NOT NULL CHECK (typeof(positive_adjustments_paise) = 'integer' AND positive_adjustments_paise >= 0),
    recovery_paise              INTEGER NOT NULL CHECK (typeof(recovery_paise) = 'integer' AND recovery_paise >= 0),
    rounding_paise              INTEGER NOT NULL CHECK (typeof(rounding_paise) = 'integer'),
    net_payable_paise           INTEGER NOT NULL CHECK (typeof(net_payable_paise) = 'integer' AND net_payable_paise >= 0),
    shortfall_treatment         TEXT    NOT NULL CHECK (shortfall_treatment IN ('NONE', 'PENDING')),
    settle_treatment            TEXT    NOT NULL CHECK (settle_treatment IN ('NONE', 'CARRY_FORWARD', 'LEAVE_OUTSTANDING',
                                    'OVERPAYMENT_TO_ADVANCE', 'OVERPAYMENT_CARRY', 'OVERPAYMENT_PENDING')),
    outstanding_at_settle_paise INTEGER CHECK (outstanding_at_settle_paise IS NULL OR typeof(outstanding_at_settle_paise) = 'integer'),
    calculated_at               TEXT    NOT NULL,
    settled_at                  TEXT,
    superseded_at               TEXT,
    superseded_reason           TEXT,
    UNIQUE (settlement_id, version_no),
    CHECK (net_payable_paise = earnings_paise + negative_adjustments_paise + shortfall_paise
                               + positive_adjustments_paise - recovery_paise + rounding_paise),
    CHECK (status <> 'SETTLED' OR (settled_at IS NOT NULL AND outstanding_at_settle_paise IS NOT NULL)),
    CHECK ((status = 'SUPERSEDED') = (superseded_at IS NOT NULL)),
    CHECK ((superseded_at IS NULL) = (superseded_reason IS NULL)),
    CHECK (superseded_reason IS NULL OR length(trim(superseded_reason)) > 0),
    CHECK (status <> 'OPEN' OR settle_treatment = 'NONE')
);

-- One current (open or settled) version per settlement.
CREATE UNIQUE INDEX uq_settlement_version_active ON settlement_version (settlement_id) WHERE status <> 'SUPERSEDED';

-- A version starts OPEN and must use a finalized, active run of the same worker and month.
CREATE TRIGGER trg_settlement_version_insert BEFORE INSERT ON settlement_version
WHEN NEW.status IS NOT 'OPEN'
  OR NOT EXISTS (SELECT 1 FROM earning_run r JOIN settlement s ON s.id = NEW.settlement_id
                 WHERE r.id = NEW.earning_run_id AND r.status = 'FINALIZED'
                   AND r.worker_id = s.worker_id AND r.period_start = s.period_start
                   AND NOT EXISTS (SELECT 1 FROM earning_run_supersession x WHERE x.superseded_run_id = r.id))
BEGIN SELECT RAISE(ABORT, 'A settlement version must start open and use the active finalized earnings of its month'); END;

-- Identity never changes. A SETTLED snapshot may only become SUPERSEDED (once, with a reason);
-- a SUPERSEDED version never changes.
CREATE TRIGGER trg_settlement_version_update BEFORE UPDATE ON settlement_version
WHEN NEW.settlement_id IS NOT OLD.settlement_id OR NEW.version_no IS NOT OLD.version_no
  OR NEW.earning_run_id IS NOT OLD.earning_run_id
  OR OLD.status = 'SUPERSEDED'
  OR (OLD.status = 'SETTLED' AND (NEW.status IS NOT 'SUPERSEDED'
      OR NEW.rounding_mode IS NOT OLD.rounding_mode OR NEW.earnings_paise IS NOT OLD.earnings_paise
      OR NEW.negative_adjustments_paise IS NOT OLD.negative_adjustments_paise OR NEW.shortfall_paise IS NOT OLD.shortfall_paise
      OR NEW.positive_adjustments_paise IS NOT OLD.positive_adjustments_paise OR NEW.recovery_paise IS NOT OLD.recovery_paise
      OR NEW.rounding_paise IS NOT OLD.rounding_paise OR NEW.net_payable_paise IS NOT OLD.net_payable_paise
      OR NEW.shortfall_treatment IS NOT OLD.shortfall_treatment OR NEW.settle_treatment IS NOT OLD.settle_treatment
      OR NEW.outstanding_at_settle_paise IS NOT OLD.outstanding_at_settle_paise
      OR NEW.calculated_at IS NOT OLD.calculated_at OR NEW.settled_at IS NOT OLD.settled_at))
BEGIN SELECT RAISE(ABORT, 'A settled or superseded settlement cannot be changed; reopen it instead'); END;

CREATE TRIGGER trg_settlement_version_no_delete BEFORE DELETE ON settlement_version
BEGIN SELECT RAISE(ABORT, 'Settlement versions cannot be deleted'); END;

-- ===================================================================== adjustments

-- Adjustments are immutable. A carry or correction moves an amount from a source settlement
-- (affects_source_cover / affects_source_shortfall) into a target month. A REVERSAL repeats
-- the reversed row's links with an opposite, no larger amount, so sums cancel exactly.
CREATE TABLE financial_adjustment (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id                INTEGER NOT NULL REFERENCES worker (id),
    kind                     TEXT    NOT NULL CHECK (kind IN ('CORRECTION', 'CARRY_FORWARD', 'OVERPAYMENT_CARRY',
                                 'CARRY_CORRECTION', 'REMAINDER_CARRY', 'REMAINDER_TO_ADVANCE', 'REVERSAL')),
    amount_paise             INTEGER NOT NULL CHECK (typeof(amount_paise) = 'integer' AND amount_paise <> 0),
    reason                   TEXT    NOT NULL CHECK (length(trim(reason)) > 0),
    adjustment_date          TEXT    NOT NULL CHECK (date(adjustment_date) IS adjustment_date),
    source_settlement_id     INTEGER REFERENCES settlement (id),
    source_version_id        INTEGER REFERENCES settlement_version (id),
    target_period_start      TEXT    CHECK (target_period_start IS NULL
                                 OR (date(target_period_start) IS target_period_start AND strftime('%d', target_period_start) = '01')),
    money_owed_by_worker     INTEGER CHECK (money_owed_by_worker IN (0, 1)),
    affects_source_cover     INTEGER NOT NULL CHECK (affects_source_cover IN (0, 1)),
    affects_source_shortfall INTEGER NOT NULL CHECK (affects_source_shortfall IN (0, 1)),
    reverses_id              INTEGER UNIQUE REFERENCES financial_adjustment (id),
    created_at               TEXT    NOT NULL,
    CHECK ((kind = 'REVERSAL') = (reverses_id IS NOT NULL)),
    -- U1: every negative (non-reversal) adjustment says whether it is money the worker owes.
    CHECK (kind = 'REVERSAL' OR amount_paise > 0 OR money_owed_by_worker IS NOT NULL),
    CHECK (kind = 'REVERSAL' OR amount_paise < 0 OR money_owed_by_worker IS NULL),
    CHECK (kind <> 'OVERPAYMENT_CARRY' OR money_owed_by_worker = 1),
    CHECK (kind <> 'CARRY_CORRECTION' OR money_owed_by_worker = 0),
    CHECK (kind <> 'CARRY_FORWARD' OR amount_paise > 0),
    CHECK (kind NOT IN ('OVERPAYMENT_CARRY', 'CARRY_CORRECTION', 'REMAINDER_CARRY', 'REMAINDER_TO_ADVANCE') OR amount_paise < 0),
    CHECK (kind IN ('CORRECTION', 'REVERSAL') OR (source_settlement_id IS NOT NULL AND source_version_id IS NOT NULL)),
    CHECK (kind IN ('REMAINDER_TO_ADVANCE', 'REVERSAL') OR target_period_start IS NOT NULL),
    CHECK (kind <> 'REMAINDER_TO_ADVANCE' OR target_period_start IS NULL),
    CHECK (kind = 'REVERSAL' OR affects_source_cover = (kind IN ('CARRY_FORWARD', 'OVERPAYMENT_CARRY', 'CARRY_CORRECTION'))),
    CHECK (kind = 'REVERSAL' OR affects_source_shortfall = (kind IN ('REMAINDER_CARRY', 'REMAINDER_TO_ADVANCE')))
);

CREATE INDEX idx_adjustment_target ON financial_adjustment (worker_id, target_period_start);
CREATE INDEX idx_adjustment_source ON financial_adjustment (source_settlement_id);

-- Q7/R6: a target month must be later than the source month and must not be settled.
CREATE TRIGGER trg_adjustment_target_valid BEFORE INSERT ON financial_adjustment
WHEN NEW.target_period_start IS NOT NULL AND (
     (NEW.source_settlement_id IS NOT NULL
      AND NEW.target_period_start <= (SELECT period_start FROM settlement WHERE id = NEW.source_settlement_id))
  OR EXISTS (SELECT 1 FROM settlement s JOIN settlement_version v ON v.settlement_id = s.id
             WHERE s.worker_id = NEW.worker_id AND s.period_start = NEW.target_period_start AND v.status = 'SETTLED'))
BEGIN SELECT RAISE(ABORT, 'The target month must be later than the source month and not already settled'); END;

-- A reversal repeats the original's links with an opposite amount no larger than the original.
CREATE TRIGGER trg_adjustment_reversal_valid BEFORE INSERT ON financial_adjustment
WHEN NEW.kind = 'REVERSAL' AND NOT EXISTS (
    SELECT 1 FROM financial_adjustment o WHERE o.id = NEW.reverses_id AND o.kind <> 'REVERSAL'
      AND o.worker_id = NEW.worker_id
      AND o.source_settlement_id IS NEW.source_settlement_id AND o.source_version_id IS NEW.source_version_id
      AND o.target_period_start IS NEW.target_period_start
      AND o.affects_source_cover = NEW.affects_source_cover AND o.affects_source_shortfall = NEW.affects_source_shortfall
      AND o.money_owed_by_worker IS NEW.money_owed_by_worker
      AND ((o.amount_paise > 0 AND NEW.amount_paise < 0 AND -NEW.amount_paise <= o.amount_paise)
        OR (o.amount_paise < 0 AND NEW.amount_paise > 0 AND NEW.amount_paise <= -o.amount_paise)))
BEGIN SELECT RAISE(ABORT, 'A reversal must mirror the adjustment it reverses'); END;

-- R5: at most one carry-forward per source settlement version.
CREATE TRIGGER trg_adjustment_one_carry BEFORE INSERT ON financial_adjustment
WHEN NEW.kind = 'CARRY_FORWARD'
 AND EXISTS (SELECT 1 FROM financial_adjustment f WHERE f.kind = 'CARRY_FORWARD' AND f.source_version_id = NEW.source_version_id)
BEGIN SELECT RAISE(ABORT, 'This settlement version has already carried its balance forward'); END;

CREATE TRIGGER trg_adjustment_immutable BEFORE UPDATE ON financial_adjustment
BEGIN SELECT RAISE(ABORT, 'Adjustments cannot be changed; record a reversal instead'); END;

CREATE TRIGGER trg_adjustment_no_delete BEFORE DELETE ON financial_adjustment
BEGIN SELECT RAISE(ABORT, 'Adjustments cannot be deleted'); END;

-- ===================================================================== payments

CREATE TABLE payment (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id           INTEGER NOT NULL REFERENCES worker (id),
    paid_on             TEXT    NOT NULL CHECK (date(paid_on) IS paid_on),
    amount_paise        INTEGER NOT NULL CHECK (typeof(amount_paise) = 'integer' AND amount_paise > 0),
    method              TEXT    NOT NULL CHECK (method IN ('CASH', 'UPI', 'BANK_TRANSFER', 'OTHER')),
    reference           TEXT,
    note                TEXT,
    allocation_mode     TEXT    NOT NULL CHECK (allocation_mode IN ('AUTO', 'EXPLICIT', 'KEEP_UNALLOCATED')),
    replaces_payment_id INTEGER UNIQUE REFERENCES payment (id),
    recorded_at         TEXT    NOT NULL
);

CREATE INDEX idx_payment_worker ON payment (worker_id, paid_on);

CREATE TRIGGER trg_payment_immutable BEFORE UPDATE ON payment
BEGIN SELECT RAISE(ABORT, 'Payments cannot be changed; void and replace them instead'); END;

CREATE TRIGGER trg_payment_no_delete BEFORE DELETE ON payment
BEGIN SELECT RAISE(ABORT, 'Payments cannot be deleted'); END;

CREATE TABLE payment_void (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    payment_id             INTEGER NOT NULL UNIQUE REFERENCES payment (id),
    reason                 TEXT    NOT NULL CHECK (length(trim(reason)) > 0),
    voided_at              TEXT    NOT NULL,
    replacement_payment_id INTEGER UNIQUE REFERENCES payment (id)
);

CREATE TRIGGER trg_payment_void_valid BEFORE INSERT ON payment_void
WHEN (NEW.replacement_payment_id IS NOT NULL
      AND (SELECT replaces_payment_id FROM payment WHERE id = NEW.replacement_payment_id) IS NOT NEW.payment_id)
  OR EXISTS (SELECT 1 FROM payment_allocation a WHERE a.payment_id = NEW.payment_id
             AND NOT EXISTS (SELECT 1 FROM allocation_reversal x WHERE x.allocation_id = a.id))
BEGIN SELECT RAISE(ABORT, 'A payment can only be voided after its allocations are reversed, with a matching replacement'); END;

CREATE TRIGGER trg_payment_void_immutable BEFORE UPDATE ON payment_void
BEGIN SELECT RAISE(ABORT, 'Payment voids cannot be changed'); END;

CREATE TRIGGER trg_payment_void_no_delete BEFORE DELETE ON payment_void
BEGIN SELECT RAISE(ABORT, 'Payment voids cannot be deleted'); END;

-- ===================================================================== advances

CREATE TABLE advance (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id            INTEGER NOT NULL REFERENCES worker (id),
    given_on             TEXT    NOT NULL CHECK (date(given_on) IS given_on),
    amount_paise         INTEGER NOT NULL CHECK (typeof(amount_paise) = 'integer' AND amount_paise > 0),
    source               TEXT    NOT NULL CHECK (source IN ('PAYMENT_EXCESS', 'OVERPAYMENT', 'REMAINDER')),
    source_settlement_id INTEGER REFERENCES settlement (id),
    source_adjustment_id INTEGER UNIQUE REFERENCES financial_adjustment (id),
    note                 TEXT,
    created_at           TEXT    NOT NULL,
    CHECK (source = 'PAYMENT_EXCESS' OR source_settlement_id IS NOT NULL),
    CHECK ((source = 'REMAINDER') = (source_adjustment_id IS NOT NULL))
);

CREATE INDEX idx_advance_worker ON advance (worker_id, given_on);

CREATE TRIGGER trg_advance_immutable BEFORE UPDATE ON advance
BEGIN SELECT RAISE(ABORT, 'Advances cannot be changed'); END;

CREATE TRIGGER trg_advance_no_delete BEFORE DELETE ON advance
BEGIN SELECT RAISE(ABORT, 'Advances cannot be deleted'); END;

-- ===================================================================== allocations

CREATE TABLE payment_allocation (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    payment_id    INTEGER NOT NULL REFERENCES payment (id),
    settlement_id INTEGER REFERENCES settlement (id),
    advance_id    INTEGER REFERENCES advance (id),
    amount_paise  INTEGER NOT NULL CHECK (typeof(amount_paise) = 'integer' AND amount_paise > 0),
    kind          TEXT    NOT NULL CHECK (kind IN ('AUTO_OLDEST_FIRST', 'EXPLICIT', 'EXCESS_TO_ADVANCE',
                      'OVERPAYMENT_TO_ADVANCE', 'KEPT_AFTER_REALLOCATION')),
    allocated_at  TEXT    NOT NULL,
    CHECK ((settlement_id IS NULL) <> (advance_id IS NULL)),
    CHECK ((advance_id IS NOT NULL) = (kind IN ('EXCESS_TO_ADVANCE', 'OVERPAYMENT_TO_ADVANCE')))
);

CREATE INDEX idx_allocation_payment ON payment_allocation (payment_id);
CREATE INDEX idx_allocation_settlement ON payment_allocation (settlement_id);

CREATE TABLE allocation_reversal (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    allocation_id INTEGER NOT NULL UNIQUE REFERENCES payment_allocation (id),
    reason        TEXT    NOT NULL CHECK (length(trim(reason)) > 0),
    reversed_at   TEXT    NOT NULL
);

-- A voided payment cannot be allocated, and active allocations never exceed the payment.
CREATE TRIGGER trg_allocation_within_payment BEFORE INSERT ON payment_allocation
WHEN EXISTS (SELECT 1 FROM payment_void v WHERE v.payment_id = NEW.payment_id)
  OR IFNULL((SELECT SUM(a.amount_paise) FROM payment_allocation a WHERE a.payment_id = NEW.payment_id
             AND NOT EXISTS (SELECT 1 FROM allocation_reversal x WHERE x.allocation_id = a.id)), 0)
     + NEW.amount_paise > (SELECT amount_paise FROM payment WHERE id = NEW.payment_id)
BEGIN SELECT RAISE(ABORT, 'Allocations cannot exceed the payment, and a voided payment cannot be allocated'); END;

-- Allocations to a settlement never exceed its payable outstanding:
-- current version net - active allocations - amounts moved out of it by carries/corrections.
CREATE TRIGGER trg_allocation_within_outstanding BEFORE INSERT ON payment_allocation
WHEN NEW.settlement_id IS NOT NULL AND
     IFNULL((SELECT v.net_payable_paise FROM settlement_version v
             WHERE v.settlement_id = NEW.settlement_id AND v.status <> 'SUPERSEDED'), -1)
     - IFNULL((SELECT SUM(a.amount_paise) FROM payment_allocation a WHERE a.settlement_id = NEW.settlement_id
               AND NOT EXISTS (SELECT 1 FROM allocation_reversal x WHERE x.allocation_id = a.id)), 0)
     - IFNULL((SELECT SUM(f.amount_paise) FROM financial_adjustment f
               WHERE f.source_settlement_id = NEW.settlement_id AND f.affects_source_cover = 1), 0)
     < NEW.amount_paise
BEGIN SELECT RAISE(ABORT, 'The allocation is more than the settlement''s payable outstanding balance'); END;

-- Allocations to a settled month or to an advance are financial history and cannot be reversed.
CREATE TRIGGER trg_allocation_reversal_valid BEFORE INSERT ON allocation_reversal
WHEN EXISTS (SELECT 1 FROM payment_allocation a WHERE a.id = NEW.allocation_id AND a.advance_id IS NOT NULL)
  OR EXISTS (SELECT 1 FROM payment_allocation a JOIN settlement_version v ON v.settlement_id = a.settlement_id
             WHERE a.id = NEW.allocation_id AND v.status = 'SETTLED')
BEGIN SELECT RAISE(ABORT, 'Allocations to a settled month or to an advance cannot be reversed; use an adjustment'); END;

CREATE TRIGGER trg_allocation_immutable BEFORE UPDATE ON payment_allocation
BEGIN SELECT RAISE(ABORT, 'Allocations cannot be changed; reverse them instead'); END;

CREATE TRIGGER trg_allocation_no_delete BEFORE DELETE ON payment_allocation
BEGIN SELECT RAISE(ABORT, 'Allocations cannot be deleted'); END;

CREATE TRIGGER trg_allocation_reversal_immutable BEFORE UPDATE ON allocation_reversal
BEGIN SELECT RAISE(ABORT, 'Allocation reversals cannot be changed'); END;

CREATE TRIGGER trg_allocation_reversal_no_delete BEFORE DELETE ON allocation_reversal
BEGIN SELECT RAISE(ABORT, 'Allocation reversals cannot be deleted'); END;

-- ===================================================================== advance recovery

CREATE TABLE advance_recovery (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    advance_id            INTEGER NOT NULL REFERENCES advance (id),
    settlement_id         INTEGER NOT NULL REFERENCES settlement (id),
    settlement_version_id INTEGER NOT NULL REFERENCES settlement_version (id),
    amount_paise          INTEGER NOT NULL CHECK (typeof(amount_paise) = 'integer' AND amount_paise > 0),
    recovered_at          TEXT    NOT NULL
);

CREATE INDEX idx_recovery_advance ON advance_recovery (advance_id);

CREATE TABLE recovery_reversal (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    recovery_id INTEGER NOT NULL UNIQUE REFERENCES advance_recovery (id),
    reason      TEXT    NOT NULL CHECK (length(trim(reason)) > 0),
    reversed_at TEXT    NOT NULL
);

-- Never recover more than the advance; one active recovery per advance and settlement;
-- the version must belong to the settlement and still be open while it is being settled.
CREATE TRIGGER trg_recovery_valid BEFORE INSERT ON advance_recovery
WHEN IFNULL((SELECT SUM(r.amount_paise) FROM advance_recovery r WHERE r.advance_id = NEW.advance_id
             AND NOT EXISTS (SELECT 1 FROM recovery_reversal x WHERE x.recovery_id = r.id)), 0)
     + NEW.amount_paise > (SELECT amount_paise FROM advance WHERE id = NEW.advance_id)
  OR EXISTS (SELECT 1 FROM advance_recovery r WHERE r.advance_id = NEW.advance_id AND r.settlement_id = NEW.settlement_id
             AND NOT EXISTS (SELECT 1 FROM recovery_reversal x WHERE x.recovery_id = r.id))
  OR NOT EXISTS (SELECT 1 FROM settlement_version v WHERE v.id = NEW.settlement_version_id
                 AND v.settlement_id = NEW.settlement_id AND v.status = 'OPEN')
BEGIN SELECT RAISE(ABORT, 'A recovery cannot exceed the advance, repeat an active recovery, or use a closed settlement version'); END;

CREATE TRIGGER trg_recovery_reversal_valid BEFORE INSERT ON recovery_reversal
WHEN NOT EXISTS (SELECT 1 FROM advance_recovery r JOIN settlement_version v ON v.settlement_id = r.settlement_id
                 WHERE r.id = NEW.recovery_id AND v.status = 'OPEN')
BEGIN SELECT RAISE(ABORT, 'A recovery can only be reversed while its month is being settled again'); END;

CREATE TRIGGER trg_recovery_immutable BEFORE UPDATE ON advance_recovery
BEGIN SELECT RAISE(ABORT, 'Recoveries cannot be changed; reverse them instead'); END;

CREATE TRIGGER trg_recovery_no_delete BEFORE DELETE ON advance_recovery
BEGIN SELECT RAISE(ABORT, 'Recoveries cannot be deleted'); END;

CREATE TRIGGER trg_recovery_reversal_immutable BEFORE UPDATE ON recovery_reversal
BEGIN SELECT RAISE(ABORT, 'Recovery reversals cannot be changed'); END;

CREATE TRIGGER trg_recovery_reversal_no_delete BEFORE DELETE ON recovery_reversal
BEGIN SELECT RAISE(ABORT, 'Recovery reversals cannot be deleted'); END;

-- ===================================================================== settlement items

CREATE TABLE settlement_item (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    version_id     INTEGER NOT NULL REFERENCES settlement_version (id),
    line_no        INTEGER NOT NULL CHECK (line_no > 0),
    item_type      TEXT    NOT NULL CHECK (item_type IN ('EARNINGS', 'ADJUSTMENT', 'SHORTFALL', 'ADVANCE_RECOVERY',
                       'ROUNDING', 'UNPAID_TIME_INFO')),
    amount_paise   INTEGER NOT NULL CHECK (typeof(amount_paise) = 'integer'),
    earning_run_id INTEGER REFERENCES earning_run (id),
    adjustment_id  INTEGER REFERENCES financial_adjustment (id),
    advance_id     INTEGER REFERENCES advance (id),
    explanation    TEXT    NOT NULL CHECK (length(trim(explanation)) > 0),
    UNIQUE (version_id, line_no),
    CHECK (item_type <> 'UNPAID_TIME_INFO' OR amount_paise = 0),
    CHECK ((item_type = 'ADJUSTMENT') = (adjustment_id IS NOT NULL)),
    CHECK ((item_type = 'ADVANCE_RECOVERY') = (advance_id IS NOT NULL)),
    CHECK (item_type <> 'ADVANCE_RECOVERY' OR amount_paise < 0),
    CHECK ((item_type = 'EARNINGS') = (earning_run_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_settlement_item_rounding ON settlement_item (version_id) WHERE item_type = 'ROUNDING';
CREATE UNIQUE INDEX uq_settlement_item_adjustment ON settlement_item (version_id, adjustment_id) WHERE adjustment_id IS NOT NULL;
CREATE UNIQUE INDEX uq_settlement_item_advance ON settlement_item (version_id, advance_id) WHERE item_type = 'ADVANCE_RECOVERY';

-- Items change only while their version is OPEN; an adjustment item must target that month.
CREATE TRIGGER trg_settlement_item_insert BEFORE INSERT ON settlement_item
WHEN (SELECT status FROM settlement_version WHERE id = NEW.version_id) IS NOT 'OPEN'
  OR (NEW.adjustment_id IS NOT NULL AND (SELECT target_period_start FROM financial_adjustment WHERE id = NEW.adjustment_id)
      IS NOT (SELECT s.period_start FROM settlement s JOIN settlement_version v ON v.settlement_id = s.id WHERE v.id = NEW.version_id))
BEGIN SELECT RAISE(ABORT, 'Settlement items can only change while the settlement is open, and adjustments must target its month'); END;

CREATE TRIGGER trg_settlement_item_update BEFORE UPDATE ON settlement_item
WHEN (SELECT status FROM settlement_version WHERE id = OLD.version_id) IS NOT 'OPEN'
BEGIN SELECT RAISE(ABORT, 'Settlement items can only change while the settlement is open'); END;

CREATE TRIGGER trg_settlement_item_delete BEFORE DELETE ON settlement_item
WHEN (SELECT status FROM settlement_version WHERE id = OLD.version_id) IS NOT 'OPEN'
BEGIN SELECT RAISE(ABORT, 'Settlement items can only change while the settlement is open'); END;
