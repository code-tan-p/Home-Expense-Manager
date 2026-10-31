-- Household expenses: categories and expense entries.
-- Like work records and payments, an expense is never edited or deleted. A correction voids the old entry
-- and inserts a replacement that points back to it, so the history stays complete.

CREATE TABLE expense_category (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    name     TEXT    NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(name)) BETWEEN 1 AND 50),
    built_in INTEGER NOT NULL DEFAULT 0 CHECK (built_in IN (0, 1))
);

INSERT INTO expense_category (name, built_in) VALUES
    ('Groceries', 1),
    ('Vegetables and fruit', 1),
    ('Electricity', 1),
    ('Water', 1),
    ('Gas', 1),
    ('Rent', 1),
    ('Internet and phone', 1),
    ('Maintenance and repairs', 1),
    ('Medical', 1),
    ('Transport', 1),
    ('Education', 1),
    ('Other', 1);

CREATE TABLE expense (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    category_id  INTEGER NOT NULL REFERENCES expense_category (id),
    spent_on     TEXT    NOT NULL CHECK (date(spent_on) IS spent_on),
    amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
    method       TEXT    NOT NULL CHECK (method IN ('CASH', 'UPI', 'CARD', 'BANK_TRANSFER')),
    note         TEXT,
    recurring    INTEGER NOT NULL DEFAULT 0 CHECK (recurring IN (0, 1)),
    recorded_at  TEXT    NOT NULL,
    replaces_id  INTEGER REFERENCES expense (id),
    voided_at    TEXT,
    void_reason  TEXT,
    CHECK ((voided_at IS NULL) = (void_reason IS NULL))
);

CREATE INDEX idx_expense_spent_on ON expense (spent_on);
CREATE INDEX idx_expense_category ON expense (category_id);

CREATE TRIGGER trg_expense_no_delete
BEFORE DELETE ON expense
BEGIN
    SELECT RAISE(ABORT, 'Expenses are never deleted. Void them instead.');
END;

CREATE TRIGGER trg_expense_only_void
BEFORE UPDATE ON expense
WHEN NEW.category_id IS NOT OLD.category_id OR NEW.spent_on IS NOT OLD.spent_on OR NEW.amount_paise IS NOT OLD.amount_paise
     OR NEW.method IS NOT OLD.method OR NEW.note IS NOT OLD.note OR NEW.recurring IS NOT OLD.recurring
     OR NEW.recorded_at IS NOT OLD.recorded_at OR NEW.replaces_id IS NOT OLD.replaces_id OR OLD.voided_at IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'An expense can only be voided, never edited.');
END;
