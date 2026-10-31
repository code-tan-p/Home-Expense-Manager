package com.homeexpense.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.homeexpense.household.HouseholdDatabases;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** A backup made by an older version of the program is accepted and brought up to date when it is opened. */
class OlderBackupRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void anOlderBackupIsRestoredAndUpgraded() throws Exception {
        Path old = tempDir.resolve("old.db");
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + old.toAbsolutePath());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("8").load().migrate();

        BackupService.validate(old);

        HouseholdDatabases databases = new HouseholdDatabases(tempDir.resolve("data"));
        Path staged = Files.copy(old, tempDir.resolve("staged.tmp"));
        databases.replace(5, staged);
        try {
            var pool = databases.open(5);
            try (var c = pool.getConnection(); var s = c.createStatement();
                 var r = s.executeQuery("SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1")) {
                r.next();
                assertThat(r.getInt(1)).isEqualTo(BackupService.newestMigration());
            }
        } finally {
            databases.close(5);
        }
    }
}
