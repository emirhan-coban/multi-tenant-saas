package com.saas.backend.config;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        applyTenant(connection);
        return connection;
    }

    private void applyTenant(Connection connection) throws SQLException {
        var tenantId = TenantContext.get();
        if (tenantId == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL app.current_tenant = '" + tenantId + "'");
        }
    }
}