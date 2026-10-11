-- Application-wide settings stored as simple key/value pairs.
CREATE TABLE app_setting (
    setting_key   TEXT PRIMARY KEY NOT NULL,
    setting_value TEXT NOT NULL
);
