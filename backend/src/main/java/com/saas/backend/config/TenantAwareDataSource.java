package com.saas.backend.config;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * İstek sahibinin işletme bağlamını veritabanı oturumuna taşır.
 *
 * <p>Faz 9 öncesinde burada {@code SET LOCAL} kullanılıyordu; işlem dışında çalıştığı için
 * etkisizdi ve uygulama süper kullanıcı olduğundan bu fark edilmiyordu. Uygulama artık RLS'i
 * atlayamayan bir rolle bağlandığından bağlam gerçekten kurulmalıdır: bağlantı havuzdan
 * alınırken oturum düzeyinde ayarlanır, havuza dönerken temizlenir. Böylece bir sonraki
 * isteğe başka bir işletmenin bağlamı sızmaz.</p>
 *
 * <p>Arka plan işleri (outbox, saklama, metrikler) HTTP bağlamı olmadan çalışır; onlar
 * bağlamı kendi işlemlerinde {@code set_config(..., true)} ile kurar.</p>
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        var tenantId = TenantContext.get();
        if (tenantId == null) {
            return connection;
        }
        try {
            applyTenant(connection, tenantId.toString());
        } catch (SQLException ex) {
            connection.close();
            throw ex;
        }
        return guard(connection);
    }

    /** Bağlantı havuza dönmeden önce bağlamı temizleyen ince bir sarmalayıcı. */
    private static Connection guard(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                TenantAwareDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        try {
                            if (!connection.isClosed()) applyTenant(connection, "");
                        } catch (SQLException ignored) {
                            // Bağlantı zaten bozuksa havuz onu atar; temizlik gerekmez.
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getTargetException();
                    }
                });
    }

    private static void applyTenant(Connection connection, String tenantId) throws SQLException {
        try (var statement = connection.prepareStatement("select set_config('app.current_tenant', ?, false)")) {
            statement.setString(1, tenantId);
            statement.execute();
        }
    }
}
