package com.homeexpense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import javax.sql.DataSource;

import com.homeexpense.model.AppSetting;
import com.homeexpense.repository.AppSettingRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.internal.database.DatabaseTypeRegister;
import org.flywaydb.core.internal.database.sqlite.SQLiteDatabaseType;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

class DatabaseFoundationIntegrationTest extends SqliteIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppSettingRepository appSettingRepository;

    @Test
    void createsMissingDataDirectoryAndDatabaseFile() {
        assertThat(DATA_DIR).isDirectory();
        assertThat(DATA_DIR.resolve("home-expense.db")).isRegularFile();
        assertThat(Files.exists(TEMP_ROOT)).isTrue();
    }

    @Test
    void flywayUsesTheApplicationDataSourceThroughSqliteJdbc() throws SQLException {
        assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);

        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDriverName()).isEqualTo("SQLite JDBC");
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:sqlite:");
            assertThat(DatabaseTypeRegister.getDatabaseTypeForConnection(connection, flyway.getConfiguration()))
                    .isInstanceOf(SQLiteDatabaseType.class);
        }
    }

    @Test
    void flywayAppliedAllMigrationsStartingWithVersionOne() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).isNotEmpty();
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied).allSatisfy(migration ->
                assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(Arrays.stream(flyway.info().pending())).isEmpty();

        Integer successfulV1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1", Integer.class);
        assertThat(successfulV1).isEqualTo(1);
    }

    @Test
    void sqliteSettingsAreAppliedToApplicationConnections() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertSqliteSettings(connection);
        }
    }

    @Test
    void sqliteSettingsAreAppliedToFlywayConnections() throws SQLException {
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            assertSqliteSettings(connection);
        }
    }

    @Test
    void sqliteSettingsAreAppliedToHibernateConnections() {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.unwrap(Session.class).doWork(this::assertSqliteSettings));
    }

    @Test
    void foreignKeyViolationsAreRejected() {
        jdbcTemplate.execute("CREATE TEMP TABLE fk_parent (id INTEGER PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TEMP TABLE fk_child (id INTEGER PRIMARY KEY, "
                + "parent_id INTEGER NOT NULL REFERENCES fk_parent (id))");
        try {
            jdbcTemplate.update("INSERT INTO fk_parent (id) VALUES (1)");
            jdbcTemplate.update("INSERT INTO fk_child (id, parent_id) VALUES (1, 1)");

            // Spring has no SQLite error-code mapping, so the violation arrives as an
            // UncategorizedSQLException; check SQLite's own result code instead.
            assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO fk_child (id, parent_id) VALUES (2, 99)"))
                    .rootCause()
                    .isInstanceOfSatisfying(SQLiteException.class, e ->
                            assertThat(e.getResultCode()).isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fk_child", Integer.class)).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("DROP TABLE fk_child");
            jdbcTemplate.execute("DROP TABLE fk_parent");
        }
    }

    @Test
    void appSettingRoundTripsThroughJpa() {
        appSettingRepository.save(new AppSetting("test.key", "test value"));

        assertThat(appSettingRepository.findById("test.key"))
                .get()
                .extracting(AppSetting::getValue)
                .isEqualTo("test value");

        appSettingRepository.deleteById("test.key");
    }

    private void assertSqliteSettings(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertThat(pragma(statement, "foreign_keys")).isEqualTo("1");
            assertThat(pragma(statement, "journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(pragma(statement, "busy_timeout")).isEqualTo("5000");
        }
    }

    private static String pragma(Statement statement, String name) throws SQLException {
        try (var resultSet = statement.executeQuery("PRAGMA " + name)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
