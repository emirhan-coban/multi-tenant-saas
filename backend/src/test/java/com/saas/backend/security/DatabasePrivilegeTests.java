package com.saas.backend.security;

import com.saas.backend.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Faz 9 — yayın öncesi güvenlik kanıtları.
 *
 * <p>Uygulamanın bağlandığı rol süper kullanıcı değildir; bu yüzden RLS "açık ama etkisiz"
 * değil, gerçekten uygulanır. Bu sınıf bunu doğrudan veritabanı üzerinden kanıtlar ve
 * HTTP katmanındaki güvenlik başlıklarını/CORS kısıtını doğrular.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatabasePrivilegeTests extends PostgresTestBase {

    @Autowired JdbcTemplate application;
    final JdbcTemplate owner = ownerJdbc();
    final JdbcTemplate restricted = appJdbc();
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void applicationConnectsWithARoleThatCannotBypassRowLevelSecurity() {
        var role = application.queryForMap("""
                select current_user as name, rolsuper, rolbypassrls, rolcreatedb, rolcreaterole
                from pg_roles where rolname = current_user
                """);
        assertEquals(APP_USER, role.get("name"));
        assertEquals(false, role.get("rolsuper"), "Uygulama rolü süper kullanıcı olmamalı");
        assertEquals(false, role.get("rolbypassrls"), "Uygulama rolü RLS'i atlamamalı");
        assertEquals(false, role.get("rolcreatedb"));
        assertEquals(false, role.get("rolcreaterole"));
    }

    @Test
    void tenantDataIsInvisibleWithoutContextAndScopedWithIt() {
        UUID first = seedTenant("Faz9 A"), second = seedTenant("Faz9 B");
        insertAppointment(first);
        insertAppointment(first);
        insertAppointment(second);

        assertEquals(0L, count(restricted, null), "Bağlam yokken hiçbir satır görünmemeli");
        assertEquals(2L, count(restricted, first));
        assertEquals(1L, count(restricted, second));
        assertTrue(count(owner, null) >= 3L, "Şema sahibi bakım için tüm satırları görebilir");
    }

    @Test
    void applicationRoleCannotChangeTheSchema() {
        var failure = assertThrows(RuntimeException.class,
                () -> restricted.execute("create table faz9_yetkisiz(id int)"));
        String reason = "";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) reason += cause.getMessage();
        assertTrue(reason.contains("permission denied"), reason);
    }

    @Test
    void backgroundJobsSeeEveryTenantOnlyThroughTheAuditedFunction() {
        seedTenant("Faz9 C");
        // Doğrudan okuma RLS'e takılır; güvenli fonksiyon yalnızca kimlikleri döndürür.
        assertEquals(0L, restricted.queryForObject("select count(*) from tenants", Long.class));
        assertTrue(restricted.queryForObject("select count(*) from ops_tenant_ids() t", Long.class) >= 1L);
    }

    @Test
    void responsesCarrySecurityHeadersAndCorsIsRestricted() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null));
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(null));
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));

        var allowed = preflight("http://localhost:5173");
        assertEquals("http://localhost:5173", allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

        var blocked = preflight("https://kotu-site.example");
        assertTrue(blocked.statusCode() >= 400, "İzinsiz kaynak reddedilmeli, gelen yanıt: " + blocked.statusCode());
        assertTrue(blocked.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/appointments"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private long count(JdbcTemplate db, UUID tenant) {
        return db.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("select set_config('app.current_tenant', '" + (tenant == null ? "" : tenant) + "', false)");
                try (var rows = statement.executeQuery("select count(*) from appointments")) {
                    rows.next();
                    return rows.getLong(1);
                }
            } finally {
                try (var reset = connection.createStatement()) {
                    reset.execute("select set_config('app.current_tenant', '', false)");
                }
            }
        });
    }

    private UUID seedTenant(String name) {
        UUID tenant = UUID.randomUUID();
        owner.update("insert into tenants(id,name,slug,timezone) values (?,?,?,?)", tenant, name, tenant.toString(), "Europe/Istanbul");
        return tenant;
    }

    private void insertAppointment(UUID tenant) {
        UUID user = UUID.randomUUID(), staff = UUID.randomUUID(), service = UUID.randomUUID();
        owner.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)", user, user + "@example.test", "hash", "Faz9");
        owner.update("insert into staff(id,tenant_id,user_id,display_name) values (?,?,?,?)", staff, tenant, user, "Faz9");
        owner.update("insert into services(id,tenant_id,name,duration_min,buffer_min,price) values (?,?,?,30,10,100)", service, tenant, "Faz9");
        owner.update("""
                insert into appointments(tenant_id,staff_id,service_id,customer_name,starts_at,ends_at,busy_until,duration_min,buffer_min,status)
                values (?,?,?,?,now() + interval '1 day', now() + interval '1 day 30 minutes', now() + interval '1 day 40 minutes',30,10,'CONFIRMED')
                """, tenant, staff, service, "Faz9");
    }
}
