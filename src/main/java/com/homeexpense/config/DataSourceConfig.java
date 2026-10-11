package com.homeexpense.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the single SQLite DataSource shared by Flyway, Hibernate and the application.
 *
 * <p>The data directory is created before the pool is built, because SQLite creates the
 * database file but not missing parent directories. Pool and SQLite driver settings come
 * from {@code spring.datasource.hikari.*} in application.properties.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties,
                                       @Value("${home-expense.data-dir}") Path dataDir) {
        ensureDataDirectory(dataDir);
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
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
            throw new IllegalStateException(
                    "The data folder " + dataDir + " is not writable. Check its permissions.");
        }
    }
}
