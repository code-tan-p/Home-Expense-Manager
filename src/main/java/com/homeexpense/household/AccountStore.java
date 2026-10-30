package com.homeexpense.household;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * The list of people and their households, in its own small file ({@code <data>/accounts.db}). It holds
 * only sign-in details, never the records themselves, so restoring or deleting a household's records
 * cannot change who can sign in.
 */
@Component
public class AccountStore {

    /** One person's sign-in details. */
    public record UserRecord(long id, String email, String name, String passwordHash, String recoveryHash, long householdId) {
    }

    private static final RowMapper<UserRecord> USER = (r, i) -> new UserRecord(r.getLong("id"), r.getString("email"),
            r.getString("name"), r.getString("password_hash"), r.getString("recovery_hash"), r.getLong("household_id"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccountStore(@Value("${home-expense.data-dir}") Path dataDir, Clock clock) throws IOException {
        java.nio.file.Files.createDirectories(dataDir);
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5000);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + dataDir.resolve("accounts.db").toAbsolutePath());
        this.jdbc = new JdbcTemplate(source);
        this.clock = clock;
        jdbc.execute("CREATE TABLE IF NOT EXISTS household (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_user (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "email TEXT NOT NULL UNIQUE, name TEXT NOT NULL, password_hash TEXT NOT NULL, recovery_hash TEXT NOT NULL, "
                + "household_id INTEGER NOT NULL UNIQUE REFERENCES household(id), created_at TEXT NOT NULL)");
    }

    public int householdCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM household", Integer.class);
    }

    public List<Long> householdIds() {
        return jdbc.queryForList("SELECT id FROM household ORDER BY id", Long.class);
    }

    /** A household with no owner yet: the records of the one-household version, waiting to be claimed. */
    public Optional<Long> unclaimedHousehold() {
        return jdbc.queryForList("SELECT h.id FROM household h LEFT JOIN app_user u ON u.household_id = h.id "
                + "WHERE u.id IS NULL ORDER BY h.id LIMIT 1", Long.class).stream().findFirst();
    }

    public synchronized long createHousehold() {
        // The insert and the id lookup must share one connection.
        return jdbc.execute((java.sql.Connection c) -> {
            try (java.sql.PreparedStatement insert = c.prepareStatement("INSERT INTO household (created_at) VALUES (?)")) {
                insert.setString(1, clock.instant().toString());
                insert.executeUpdate();
            }
            try (java.sql.Statement s = c.createStatement(); java.sql.ResultSet r = s.executeQuery("SELECT last_insert_rowid()")) {
                r.next();
                return r.getLong(1);
            }
        });
    }

    /** Adds a person to a household; false when the email is already used. */
    public synchronized boolean addUser(String email, String name, String passwordHash, String recoveryHash, long householdId) {
        try {
            jdbc.update("INSERT INTO app_user (email, name, password_hash, recovery_hash, household_id, created_at) VALUES (?,?,?,?,?,?)",
                    email, name, passwordHash, recoveryHash, householdId, clock.instant().toString());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return false;
        }
    }

    public Optional<UserRecord> findByEmail(String email) {
        return jdbc.query("SELECT * FROM app_user WHERE email = ?", USER, email).stream().findFirst();
    }

    public Optional<UserRecord> findById(long id) {
        return jdbc.query("SELECT * FROM app_user WHERE id = ?", USER, id).stream().findFirst();
    }

    public void updatePassword(long userId, String passwordHash) {
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = ?", passwordHash, userId);
    }

    public void updateRecovery(long userId, String recoveryHash) {
        jdbc.update("UPDATE app_user SET recovery_hash = ? WHERE id = ?", recoveryHash, userId);
    }

    public synchronized void deleteHousehold(long householdId) {
        jdbc.update("DELETE FROM app_user WHERE household_id = ?", householdId);
        jdbc.update("DELETE FROM household WHERE id = ?", householdId);
    }
}
