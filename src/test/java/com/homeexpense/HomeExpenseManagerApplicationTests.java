package com.homeexpense;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

class HomeExpenseManagerApplicationTests extends SqliteIntegrationTest {

    @Value("${server.address}")
    private String serverAddress;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Test
    void contextLoads() {
    }

    @Test
    void serverIsBoundToLocalhostOnly() {
        assertThat(serverAddress).isEqualTo("127.0.0.1");
    }

    @Test
    void testsUseTemporaryDatabaseNotTheRealOne() {
        assertThat(datasourceUrl).isEqualTo("jdbc:sqlite:" + DATA_DIR.resolve("home-expense.db"));
        assertThat(datasourceUrl).doesNotContain(".home-expense-manager");
    }
}
