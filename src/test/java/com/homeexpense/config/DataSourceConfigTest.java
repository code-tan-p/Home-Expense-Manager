package com.homeexpense.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataSourceConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void pendingRestoreReplacesTheLiveDatabaseAndKeepsTheOldOne() throws IOException {
        Files.writeString(tempDir.resolve("home-expense.db"), "old");
        Files.writeString(tempDir.resolve("home-expense.db-wal"), "stale log");
        Files.writeString(tempDir.resolve("home-expense.db-shm"), "stale index");
        Files.writeString(tempDir.resolve("restore-pending.db"), "from backup");

        boolean applied = DataSourceConfig.applyPendingRestore(tempDir);

        assertThat(applied).isTrue();
        assertThat(Files.readString(tempDir.resolve("home-expense.db"))).isEqualTo("from backup");
        assertThat(Files.readString(tempDir.resolve("home-expense.db.before-restore"))).isEqualTo("old");
        assertThat(tempDir.resolve("home-expense.db-wal")).doesNotExist();
        assertThat(tempDir.resolve("home-expense.db-shm")).doesNotExist();
        assertThat(tempDir.resolve("restore-pending.db")).doesNotExist();
    }

    @Test
    void noPendingRestoreChangesNothing() throws IOException {
        Files.writeString(tempDir.resolve("home-expense.db"), "live");

        assertThat(DataSourceConfig.applyPendingRestore(tempDir)).isFalse();
        assertThat(Files.readString(tempDir.resolve("home-expense.db"))).isEqualTo("live");
    }

    @Test
    void createsMissingNestedDirectories() {
        Path dataDir = tempDir.resolve("a").resolve("b");

        DataSourceConfig.ensureDataDirectory(dataDir);

        assertThat(dataDir).isDirectory();
    }

    @Test
    void acceptsExistingDirectory() {
        DataSourceConfig.ensureDataDirectory(tempDir);
        DataSourceConfig.ensureDataDirectory(tempDir);

        assertThat(tempDir).isDirectory();
    }

    @Test
    void refusesPathThatIsAFile() throws IOException {
        Path file = Files.createFile(tempDir.resolve("not-a-folder"));

        assertThatThrownBy(() -> DataSourceConfig.ensureDataDirectory(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is a file, not a folder");
    }
}
