package com.homeexpense.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.homeexpense.config.AttemptLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HouseholdDatabasesTest {

    @TempDir
    Path tempDir;

    @Test
    void createsMissingNestedFolders() {
        Path data = tempDir.resolve("a").resolve("b");
        new HouseholdDatabases(data);
        assertThat(data.resolve("households")).isDirectory();
    }

    @Test
    void refusesADataFolderThatIsAFile() throws IOException {
        Path file = Files.createFile(tempDir.resolve("not-a-folder"));
        assertThatThrownBy(() -> new HouseholdDatabases(file)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is a file, not a folder");
    }

    @Test
    void replaceKeepsTheOldFileAndRemovesStaleLogs() throws IOException {
        HouseholdDatabases dbs = new HouseholdDatabases(tempDir);
        Files.writeString(dbs.file(7), "old");
        Files.writeString(tempDir.resolve("households").resolve("7.db-wal"), "stale");
        Path checked = Files.writeString(tempDir.resolve("check.tmp"), "from backup");

        dbs.replace(7, checked);

        assertThat(Files.readString(dbs.file(7))).isEqualTo("from backup");
        assertThat(Files.readString(tempDir.resolve("households").resolve("7.db.before-restore"))).isEqualTo("old");
        assertThat(tempDir.resolve("households").resolve("7.db-wal")).doesNotExist();
    }

    @Test
    void anOldOneHouseholdDatabaseIsMovedIn() throws IOException {
        HouseholdDatabases dbs = new HouseholdDatabases(tempDir);
        Files.writeString(tempDir.resolve("home-expense.db"), "earlier records");

        assertThat(dbs.adoptLegacyDatabase(1)).isTrue();

        assertThat(Files.readString(dbs.file(1))).isEqualTo("earlier records");
        assertThat(tempDir.resolve("home-expense.db")).doesNotExist();
        assertThat(dbs.adoptLegacyDatabase(2)).isFalse();
    }

    @Test
    void anUnboundThreadCannotReachAnyDatabase() {
        HouseholdContext context = new HouseholdContext("");
        assertThatThrownBy(context::current).isInstanceOf(IllegalStateException.class);
        context.run(5, () -> assertThat(context.current()).isEqualTo(5));
        assertThatThrownBy(context::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void limiterBlocksAfterTheMaximumAndForgetsAfterTheWindow() {
        long[] now = {0};
        Clock clock = new Clock() {
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return Instant.ofEpochSecond(now[0]); }
        };
        AttemptLimiter limiter = new AttemptLimiter(clock, 3, Duration.ofMinutes(10));
        limiter.record("k");
        limiter.record("k");
        assertThat(limiter.blocked("k")).isFalse();
        limiter.record("k");
        assertThat(limiter.blocked("k")).isTrue();
        assertThat(limiter.blocked("other")).isFalse();
        now[0] = 601;
        assertThat(limiter.blocked("k")).isFalse();
    }
}
