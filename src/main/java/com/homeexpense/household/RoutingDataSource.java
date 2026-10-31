package com.homeexpense.household;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;

/** Hands out a connection to the database of the household bound to the current thread. */
public class RoutingDataSource extends AbstractDataSource {

    private final HouseholdContext context;
    private final HouseholdDatabases databases;

    public RoutingDataSource(HouseholdContext context, HouseholdDatabases databases) {
        this.context = context;
        this.databases = databases;
    }

    private DataSource target() {
        return databases.open(context.current());
    }

    @Override
    public Connection getConnection() throws SQLException {
        return target().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }
}
