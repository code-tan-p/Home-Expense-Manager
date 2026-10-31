package com.homeexpense.config;

import javax.sql.DataSource;

import com.homeexpense.household.HouseholdContext;
import com.homeexpense.household.HouseholdDatabases;
import com.homeexpense.household.RoutingDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The application DataSource does not point at one file: each request is routed to the database of the
 * household it belongs to. Flyway runs per household when its file is opened (HouseholdDatabases),
 * so Spring's own Flyway start-up step is switched off.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(HouseholdContext context, HouseholdDatabases databases) {
        return new RoutingDataSource(context, databases);
    }
}
