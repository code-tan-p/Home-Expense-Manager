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
