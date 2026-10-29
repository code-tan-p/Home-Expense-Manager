-- Append-only audit history. Rows can be added but never changed or deleted.

CREATE TABLE audit_event (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at TEXT    NOT NULL,
    worker_id   INTEGER REFERENCES worker (id),
    entity_type TEXT    NOT NULL,
    entity_id   INTEGER NOT NULL,
    action      TEXT    NOT NULL,
    details     TEXT
);

CREATE INDEX idx_audit_event_worker ON audit_event (worker_id, occurred_at);
CREATE INDEX idx_audit_event_entity ON audit_event (entity_type, entity_id);

CREATE TRIGGER trg_audit_event_no_update
BEFORE UPDATE ON audit_event
BEGIN
    SELECT RAISE(ABORT, 'Audit events cannot be changed');
END;

CREATE TRIGGER trg_audit_event_no_delete
BEFORE DELETE ON audit_event
BEGIN
    SELECT RAISE(ABORT, 'Audit events cannot be deleted');
END;
