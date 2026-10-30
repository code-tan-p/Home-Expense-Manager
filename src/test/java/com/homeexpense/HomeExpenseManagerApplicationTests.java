package com.homeexpense;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

class HomeExpenseManagerApplicationTests extends SqliteIntegrationTest {

    @Value("${server.address}")
    private String serverAddress;

    @Autowired
    private com.homeexpense.household.HouseholdDatabases databases;

    @Test
    void contextLoads() {
    }

    @Test
    void serverIsBoundToLocalhostOnly() {
        assertThat(serverAddress).isEqualTo("127.0.0.1");
    }

    @Test
    void testsUseTemporaryDatabaseNotTheRealOne() {
        databases.open(1);
        assertThat(databases.file(1)).isRegularFile();
        assertThat(databases.file(1).startsWith(DATA_DIR)).isTrue();
        assertThat(databases.file(1).toString()).doesNotContain(".home-expense-manager");
    }
}
