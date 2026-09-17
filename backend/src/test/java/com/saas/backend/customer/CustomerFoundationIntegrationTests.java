package com.saas.backend.customer;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerFoundationIntegrationTests extends PostgresTestBase {
    // Faz 9: fixture SQL'i şema sahibiyle çalışır; uygulama kodu kısıtlı saas_app rolündedir.
    final JdbcTemplate db = ownerJdbc();
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private UUID staffTenant;

    @BeforeEach
    void setup() {
        staffTenant = UUID.randomUUID();
        db.update("insert into tenants(id,name,slug,timezone) values (?,?,?,?)",
                staffTenant, "Security QA", staffTenant.toString(), "Europe/Istanbul");
    }

    @Test
    void registerNormalizesEmailHashesPasswordAndSupportsLoginRefreshAndMe() throws Exception {
        var registered = post("/api/customer/v1/auth/register", """
                {"email":"Customer@Example.COM","password":"secret123","fullName":"  Ada Lovelace  ","phone":"+90 555 000 00 00"}
                """, null);
        assertEquals(201, registered.statusCode(), registered.body());
        var body = json.readTree(registered.body());
        var customerId = UUID.fromString(body.get("customer").get("id").asString());
        assertEquals("customer@example.com", body.get("customer").get("email").asString());
        assertEquals("Ada Lovelace", body.get("customer").get("fullName").asString());
        assertEquals("customer_access", jwt.parseCustomerAccessToken(body.get("accessToken").asString()).get("type"));
        assertEquals("customer_refresh", jwt.parseCustomerRefreshToken(body.get("refreshToken").asString()).get("type"));

        var stored = db.queryForMap("select email,password_hash,full_name from customer_accounts where id=?", customerId);
        assertEquals("customer@example.com", stored.get("email"));
        assertEquals("Ada Lovelace", stored.get("full_name"));
        assertNotEquals("secret123", stored.get("password_hash"));
        assertTrue(stored.get("password_hash").toString().startsWith("$2"));

        var login = post("/api/customer/v1/auth/login",
                "{\"email\":\"CUSTOMER@example.com\",\"password\":\"secret123\"}", null);
        assertEquals(200, login.statusCode(), login.body());
        var loginBody = json.readTree(login.body());

        var me = get("/api/customer/v1/me", loginBody.get("accessToken").asString());
        assertEquals(200, me.statusCode(), me.body());
        assertEquals(customerId.toString(), json.readTree(me.body()).get("id").asString());

        var refresh = post("/api/customer/v1/auth/refresh",
                "{\"refreshToken\":\"" + loginBody.get("refreshToken").asString() + "\"}", null);
        assertEquals(200, refresh.statusCode(), refresh.body());
        assertNotEquals(loginBody.get("accessToken").asString(), json.readTree(refresh.body()).get("accessToken").asString());
    }

    @Test
    void duplicateInvalidCredentialsAndInvalidRefreshUseStableErrors() throws Exception {
        String register = "{\"email\":\"duplicate@example.com\",\"password\":\"secret123\",\"fullName\":\"Test User\"}";
        assertEquals(201, post("/api/customer/v1/auth/register", register, null).statusCode());
        assertError(post("/api/customer/v1/auth/register", register, null), 409, "CUSTOMER_EMAIL_EXISTS");
        assertError(post("/api/customer/v1/auth/login",
                "{\"email\":\"duplicate@example.com\",\"password\":\"wrong\"}", null),
                401, "INVALID_CUSTOMER_CREDENTIALS");
        assertError(post("/api/customer/v1/auth/refresh", "{\"refreshToken\":\"not-a-token\"}", null),
                401, "INVALID_CUSTOMER_REFRESH_TOKEN");
    }

    @Test
    void customerAndStaffTokensCannotCrossSecurityBoundary() throws Exception {
        var registered = post("/api/customer/v1/auth/register",
                "{\"email\":\"boundary@example.com\",\"password\":\"secret123\",\"fullName\":\"Boundary User\"}", null);
        JsonNode body = json.readTree(registered.body());
        String customerAccess = body.get("accessToken").asString();
        String customerRefresh = body.get("refreshToken").asString();

        assertError(get("/api/customer/v1/me", customerRefresh), 401, "UNAUTHORIZED");
        assertError(get("/api/me", customerAccess), 401, "UNAUTHORIZED");
        String staffAccess = jwt.generateAccessToken(UUID.randomUUID(), staffTenant, "OWNER");
        assertError(get("/api/customer/v1/me", staffAccess), 403, "FORBIDDEN");
        assertError(post("/api/customer/v1/auth/refresh",
                "{\"refreshToken\":\"" + customerAccess + "\"}", null),
                401, "INVALID_CUSTOMER_REFRESH_TOKEN");
    }

    @Test
    void tenantCustomerAndBookingRequestRlsUsesTransactionLocalTenant() throws Exception {
        UUID customerAccount = UUID.randomUUID();
        db.update("insert into customer_accounts(id,email,password_hash,full_name) values (?,?,?,?)",
                customerAccount, customerAccount + "@example.test", "hash", "RLS Customer");
        var first = seedBookingGraph(customerAccount);
        var second = seedBookingGraph(customerAccount);

        String role = "customer_rls_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("create role " + role);
        db.execute("grant usage on schema public to " + role);
        db.execute("grant select on tenant_customers,booking_requests to " + role);

        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("set local role " + role);
                statement.execute("select set_config('app.current_tenant','" + first.tenant() + "',true)");
                for (String table : java.util.List.of("tenant_customers", "booking_requests")) {
                    try (var rows = statement.executeQuery("select count(*) from " + table)) {
                        rows.next();
                        assertEquals(1, rows.getInt(1));
                    }
                }
                connection.commit();
                statement.execute("set local role " + role);
                try (var rows = statement.executeQuery("select count(*) from booking_requests")) {
                    rows.next();
                    assertEquals(0, rows.getInt(1));
                }
            } finally {
                connection.rollback();
            }
        }

        assertThrows(DataIntegrityViolationException.class, () -> db.update("""
                insert into booking_requests(tenant_id,customer_id,staff_id,service_id,starts_at,busy_until,status,decision,hold_expires_at,idempotency_key,request_hash)
                values (?,?,?,?,?::timestamptz,?::timestamptz,'PENDING_APPROVAL','REQUIRE_APPROVAL',?::timestamptz,?,?)
                """, first.tenant(), second.customer(), first.staff(), first.service(),
                Instant.parse("2030-01-07T09:00:00Z").toString(), Instant.parse("2030-01-07T09:40:00Z").toString(), Instant.parse("2030-01-07T08:15:00Z").toString(),
                "cross-tenant", "a".repeat(64)));
    }

    private BookingGraph seedBookingGraph(UUID customerAccount) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), staff = UUID.randomUUID();
        UUID service = UUID.randomUUID(), customer = UUID.randomUUID();
        db.update("insert into tenants(id,name,slug,timezone) values (?,?,?,?)", tenant, "RLS", tenant.toString(), "Europe/Istanbul");
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)", user, user + "@example.test", "hash", "RLS Staff");
        db.update("insert into staff(id,tenant_id,user_id,display_name) values (?,?,?,?)", staff, tenant, user, "RLS Staff");
        db.update("insert into services(id,tenant_id,name,duration_min,buffer_min,price) values (?,?,?,30,10,100)", service, tenant, "Cut");
        db.update("insert into tenant_customers(id,tenant_id,customer_account_id) values (?,?,?)", customer, tenant, customerAccount);
        db.update("""
                insert into booking_requests(tenant_id,customer_id,staff_id,service_id,starts_at,busy_until,status,decision,hold_expires_at,idempotency_key,request_hash)
                values (?,?,?,?,?::timestamptz,?::timestamptz,'PENDING_APPROVAL','REQUIRE_APPROVAL',?::timestamptz,?,?)
                """, tenant, customer, staff, service, Instant.parse("2030-01-07T09:00:00Z").toString(),
                Instant.parse("2030-01-07T09:40:00Z").toString(), Instant.parse("2030-01-07T08:15:00Z").toString(), UUID.randomUUID().toString(), "b".repeat(64));
        return new BookingGraph(tenant, customer, staff, service);
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).get("code").asString(), response.body());
    }

    private record BookingGraph(UUID tenant, UUID customer, UUID staff, UUID service) {
    }
}
