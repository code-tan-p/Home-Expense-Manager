package com.homeexpense.household;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One SQLite file per household, under {@code <data>/households/<id>.db}. A household's records can only be
 * reached through its own file, so one household can never see another's. A file is created and brought up to
 * date (Flyway) the first time it is opened. Idle connections close after ten seconds, so many households
 * do not keep many files open.
 */
@Component
public class HouseholdDatabases implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HouseholdDatabases.class);
    static final String LEGACY_DATABASE = "home-expense.db";

    private final Path folder;
    private final Path dataDir;
    private final Map<Long, HikariDataSource> open = new ConcurrentHashMap<>();

    public HouseholdDatabases(@Value("${home-expense.data-dir}") Path dataDir) {
        this.dataDir = dataDir;
        this.folder = dataDir.resolve("households");
        ensureDataDirectory(dataDir);
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the folder " + folder + ". Check that you can write there.", e);
        }
    }

    public Path file(long householdId) {
        return folder.resolve(householdId + ".db");
    }

    public boolean exists(long householdId) {
        return Files.exists(file(householdId));
    }

    /** The household's connection pool, creating and upgrading its database on first use. */
    public HikariDataSource open(long householdId) {
        return open.computeIfAbsent(householdId, this::create);
    }

    private HikariDataSource create(long householdId) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("household-" + householdId);
        config.setJdbcUrl("jdbc:sqlite:" + file(householdId).toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setIdleTimeout(10_000);
        config.addDataSourceProperty("foreign_keys", "true");
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("busy_timeout", "5000");
        HikariDataSource pool = new HikariDataSource(config);
        try {
            Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate();
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
        return pool;
    }

    /** Closes the household's pool so its files can be replaced or deleted. */
    public void close(long householdId) {
        HikariDataSource pool = open.remove(householdId);
        if (pool != null) {
            pool.close();
        }
    }

    /** Replaces the household's database by a checked file. The old one is kept beside it as a safety copy. */
    public void replace(long householdId, Path checked) throws IOException {
        close(householdId);
        Path live = file(householdId);
        if (Files.exists(live)) {
            Files.move(live, folder.resolve(householdId + ".db.before-restore"), StandardCopyOption.REPLACE_EXISTING);
        }
        deleteSideFiles(householdId);
        Files.move(checked, live, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Removes the household's database and automatic backups for good. */
    public void delete(long householdId) throws IOException {
        close(householdId);
        Files.deleteIfExists(file(householdId));
        Files.deleteIfExists(folder.resolve(householdId + ".db.before-restore"));
        deleteSideFiles(householdId);
        Path backups = dataDir.resolve("backups").resolve(String.valueOf(householdId));
        if (Files.isDirectory(backups)) {
            try (Stream<Path> files = Files.walk(backups)) {
                for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** Moves a database from the one-household version of this program in as the given household. */
    public boolean adoptLegacyDatabase(long householdId) throws IOException {
        Path legacy = dataDir.resolve(LEGACY_DATABASE);
        if (!Files.exists(legacy)) {
            return false;
        }
        Files.move(legacy, file(householdId), StandardCopyOption.REPLACE_EXISTING);
        for (String suffix : new String[] {"-wal", "-shm"}) {
            Path side = dataDir.resolve(LEGACY_DATABASE + suffix);
            if (Files.exists(side)) {
                Files.move(side, folder.resolve(householdId + ".db" + suffix), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log.info("Existing records moved to household {}", householdId);
        return true;
    }

    private void deleteSideFiles(long householdId) throws IOException {
        Files.deleteIfExists(folder.resolve(householdId + ".db-wal"));
        Files.deleteIfExists(folder.resolve(householdId + ".db-shm"));
    }

    static void ensureDataDirectory(Path dataDir) {
        if (Files.exists(dataDir) && !Files.isDirectory(dataDir)) {
            throw new IllegalStateException(
                    "The data folder " + dataDir + " exists but is a file, not a folder. "
                            + "Move or rename that file, then start the application again.");
        }
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not create the data folder " + dataDir + ". Check that you have permission to write there.", e);
        }
        if (!Files.isWritable(dataDir)) {
            throw new IllegalStateException("The data folder " + dataDir + " is not writable. Check its permissions.");
        }
    }

    @Override
    public void destroy() {
        open.keySet().forEach(this::close);
    }
}
