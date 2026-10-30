package com.homeexpense.household;

import java.io.IOException;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records made with the one-household version of this program ({@code home-expense.db}) are moved in as a
 * household that has no owner yet. The first person to sign up takes it over, so nothing is lost.
 */
@Component
public class LegacyMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyMigration.class);

    private final AccountStore accounts;
    private final HouseholdDatabases databases;

    public LegacyMigration(AccountStore accounts, HouseholdDatabases databases) {
        this.accounts = accounts;
        this.databases = databases;
    }

    @PostConstruct
    void adoptOldRecords() throws IOException {
        if (accounts.householdCount() > 0) {
            return;
        }
        long household = accounts.createHousehold();
        if (!databases.adoptLegacyDatabase(household)) {
            accounts.deleteHousehold(household);
            return;
        }
        log.info("Your earlier records are kept. Sign up to take them over.");
    }
}
