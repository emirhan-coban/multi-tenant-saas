package com.saas.backend;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Tek kullanımlık bir veritabanı: testler asla geliştiricinin saasdb'sine bağlanmaz.
 *
 * <p>Faz 9: uygulama bağlantısı üretimdeki gibi <b>süper kullanıcı olmayan</b> {@code saas_app}
 * rolüyle açılır. Böylece her entegrasyon testi RLS'in gerçekten uygulandığını kanıtlar;
 * Flyway göçleri şema sahibi (container'ın kendi kullanıcısı) ile çalışır.</p>
 */
public abstract class PostgresTestBase {
    protected static final String APP_USER = "saas_app";
    protected static final String APP_PASSWORD = "saas_app_test";
    protected static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16-alpine");
    protected static final org.testcontainers.containers.GenericContainer<?> REDIS=new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static {
        POSTGRES.start();
        REDIS.start();
        createApplicationRole();
    }

    /**
     * ops/db/01-app-role.sh ile aynı yetkileri verir. Flyway'den önce çalıştığı için
     * "default privileges" sayesinde göçlerin oluşturacağı tablolar da otomatik yetkilenir.
     */
    private static void createApplicationRole() {
        String owner = POSTGRES.getUsername();
        String sql = """
                do $$
                declare app text := %s; pwd text := %s; owner text := %s;
                begin
                    if not exists (select 1 from pg_roles where rolname = app) then
                        execute format('create role %%I login', app);
                    end if;
                    execute format('alter role %%I with login nosuperuser nocreatedb nocreaterole nobypassrls password %%L', app, pwd);
                    execute format('grant connect on database %%I to %%I', current_database(), app);
                    execute format('grant usage on schema public to %%I', app);
                    execute format('grant select, insert, update, delete on all tables in schema public to %%I', app);
                    execute format('grant usage, select on all sequences in schema public to %%I', app);
                    execute format('grant execute on all functions in schema public to %%I', app);
                    execute format('alter default privileges for role %%I in schema public grant select, insert, update, delete on tables to %%I', owner, app);
                    execute format('alter default privileges for role %%I in schema public grant usage, select on sequences to %%I', owner, app);
                    execute format('alter default privileges for role %%I in schema public grant execute on functions to %%I', owner, app);
                    execute format('revoke create on schema public from %%I', app);
                end $$;
                """.formatted(quote(APP_USER), quote(APP_PASSWORD), quote(owner));
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), owner, POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException("Test veritabanında uygulama rolü oluşturulamadı", ex);
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static org.springframework.jdbc.core.JdbcTemplate OWNER_JDBC;
    private static org.springframework.jdbc.core.JdbcTemplate APP_JDBC;

    /**
     * Test verisi kuran doğrudan SQL'ler şema sahibiyle çalışır: bir yöneticinin
     * veritabanına veri yazmasıyla aynıdır ve RLS'e takılmaz. Test edilen uygulama kodu
     * (Spring context'teki DataSource) her zaman kısıtlı {@code saas_app} rolünü kullanır.
     */
    protected static synchronized org.springframework.jdbc.core.JdbcTemplate ownerJdbc() {
        if (OWNER_JDBC == null) OWNER_JDBC = template(POSTGRES.getUsername(), POSTGRES.getPassword());
        return OWNER_JDBC;
    }

    /** RLS'in gerçekten uygulandığını doğrulayan testler için kısıtlı rolle bağlantı. */
    protected static synchronized org.springframework.jdbc.core.JdbcTemplate appJdbc() {
        if (APP_JDBC == null) APP_JDBC = template(APP_USER, APP_PASSWORD);
        return APP_JDBC;
    }

    private static org.springframework.jdbc.core.JdbcTemplate template(String user, String password) {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password);
        source.setDriverClassName("org.postgresql.Driver");
        return new org.springframework.jdbc.core.JdbcTemplate(source);
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",()->APP_USER);
        registry.add("spring.datasource.password",()->APP_PASSWORD);
        // Göçler şema sahibiyle çalışır; uygulama rolünün DDL yetkisi yoktur.
        registry.add("spring.flyway.user",POSTGRES::getUsername);
        registry.add("spring.flyway.password",POSTGRES::getPassword);
        registry.add("app.redis-url",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
        registry.add("app.outbox.initial-delay-ms",()->"3600000");
        // Faz 8 zamanlanmış işleri testlerde kendiliğinden çalışmaz; testler ilgili servisi doğrudan çağırır.
        registry.add("app.push.initial-delay-ms",()->"3600000");
        registry.add("app.metrics.queue-initial-delay-ms",()->"3600000");
        registry.add("app.retention.cron",()->"-");
    }
}
