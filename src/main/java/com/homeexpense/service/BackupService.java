package com.homeexpense.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.household.AccountStore;
import com.homeexpense.household.HouseholdContext;
import com.homeexpense.household.HouseholdDatabases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.sqlite.SQLiteConfig;
import org.springframework.stereotype.Service;

/**
 * Backups of one household's records: a download copy, automatic daily copies, and a safe restore.
 *
 * <p>{@code VACUUM INTO} writes one complete, consistent file (the write-ahead log is already folded in),
 * so a single file is a full backup. A restore replaces only the signed-in household's file, after the
 * file has been checked.
 */
@Service
public class BackupService {

    static final String PENDING_RESTORE = "restore-pending.db";
    static final int KEEP_AUTOMATIC = 14;
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__.*\\.sql");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Path dataDir;
    private final HouseholdContext households;
    private final HouseholdDatabases databases;
    private final AccountStore accounts;

    public BackupService(JdbcTemplate jdbc, Clock clock, @Value("${home-expense.data-dir}") Path dataDir,
                         HouseholdContext households, HouseholdDatabases databases, AccountStore accounts) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.dataDir = dataDir;
        this.households = households;
        this.databases = databases;
        this.accounts = accounts;
    }

    /** A complete copy of the database, held in memory so no temporary file can be left behind. */
    public byte[] snapshot() throws IOException {
        Path folder = Files.createTempDirectory("home-expense-backup");
        Path copy = folder.resolve("copy.db");
        try {
            writeCopy(copy);
            return Files.readAllBytes(copy);
        } finally {
            Files.deleteIfExists(copy);
            Files.deleteIfExists(folder);
        }
    }

    public String downloadName() {
        return "home-expense-" + LocalDate.now(clock) + ".db";
    }

    /** Checks an uploaded backup, then swaps it in for the signed-in household at once. The old records are kept as a safety copy. */
    public void restore(InputStream upload) throws IOException {
        long household = households.current();
        Path check = dataDir.resolve("restore-check-" + household + ".tmp");
        try {
            Files.copy(upload, check, StandardCopyOption.REPLACE_EXISTING);
            validate(check);
            databases.replace(household, check);
        } finally {
            Files.deleteIfExists(check);
        }
    }

    /** Opens the file read-only and refuses anything that is not a healthy backup of this program, or is from a newer version. */
    static void validate(Path file) {
        final String notABackup = "That file is not a Home Expense Manager backup. Choose the file you downloaded from this page.";
        try (Connection c = readOnly(file); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
                if (!r.next() || !"ok".equals(r.getString(1))) {
                    throw new BusinessRuleException("That backup file is damaged, so it was not used. Your records were not changed.");
                }
            }
            try (ResultSet r = s.executeQuery("SELECT 1 FROM sqlite_master WHERE name = 'flyway_schema_history'")) {
                if (!r.next()) {
                    throw new BusinessRuleException(notABackup);
                }
            }
            try (ResultSet r = s.executeQuery("SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1")) {
                int installed = r.next() ? r.getInt(1) : 0;
                if (installed < 1) {
                    throw new BusinessRuleException(notABackup);
                }
                if (installed > newestMigration()) {
                    throw new BusinessRuleException("That backup was made by a newer version of this program. "
                            + "Update the program first, then restore it.");
                }
            }
        } catch (SQLException e) {
            throw new BusinessRuleException(notABackup);
        }
    }

    private static Connection readOnly(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return config.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
    }

    /** The highest migration number that ships with this program. */
    static int newestMigration() {
        try {
            int newest = 0;
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")) {
                Matcher m = MIGRATION_FILE.matcher(String.valueOf(resource.getFilename()));
                if (m.matches()) {
                    newest = Math.max(newest, Integer.parseInt(m.group(1)));
                }
            }
            return newest;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the program's database versions.", e);
        }
    }

    /** Every day at 02:00 each household gets a copy in the data folder; the newest {@value #KEEP_AUTOMATIC} are kept. */
    @Scheduled(cron = "${home-expense.auto-backup-cron:0 0 2 * * *}")
    public void automaticBackup() {
        for (long household : accounts.householdIds()) {
            if (!databases.exists(household)) {
                continue;
            }
            try {
                households.run(household, () -> backUpCurrentHousehold(household));
            } catch (RuntimeException e) {
                log.warn("Automatic backup failed for household {}.", household, e);
            }
        }
    }

    public void backUpCurrentHousehold(long household) {
        try {
            Path folder = dataDir.resolve("backups").resolve(String.valueOf(household));
            Files.createDirectories(folder);
            Path target = folder.resolve("home-expense-" + LocalDate.now(clock) + ".db");
            Files.deleteIfExists(target);
            writeCopy(target);
            pruneAutomaticBackups(folder);
            log.info("Automatic backup written to {}", target);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeCopy(Path target) {
        jdbc.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
    }

    private static void pruneAutomaticBackups(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> old = files.filter(p -> p.getFileName().toString().matches("home-expense-\\d{4}-\\d{2}-\\d{2}\\.db"))
                    .sorted().toList();
            for (int i = 0; i < old.size() - KEEP_AUTOMATIC; i++) {
                Files.deleteIfExists(old.get(i));
            }
        }
    }
}
