-- Household-wide lists of tasks (sweeping, cooking, milk delivery, ...) and named shifts.
-- Times are TEXT 'HH:MM' on a 24-hour clock (00:00-23:59; SQLite itself would accept 24:00,
-- so it is excluded explicitly). Shifts may not cross midnight in version 1.

CREATE TABLE task (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    name   TEXT    NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(name)) BETWEEN 1 AND 100),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

CREATE TABLE shift (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT    NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(name)) BETWEEN 1 AND 50),
    default_start TEXT    NOT NULL CHECK (strftime('%H:%M', default_start) IS default_start AND default_start < '24:00'),
    default_end   TEXT    NOT NULL CHECK (strftime('%H:%M', default_end) IS default_end AND default_end < '24:00'),
    active        INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    CHECK (default_end > default_start)
);
