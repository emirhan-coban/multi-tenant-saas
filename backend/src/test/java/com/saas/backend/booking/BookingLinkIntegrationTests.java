package com.saas.backend.booking;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingLinkIntegrationTests extends PostgresTestBase {
    @Autowired JwtService jwt;
    // Faz 9: fixture SQL'i şema sahibiyle çalışır; uygulama kodu kısıtlı saas_app rolündedir.
    final JdbcTemplate db = ownerJdbc();
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private UUID user;
    private UUID firstTenant;
    private UUID secondTenant;

    @BeforeEach
    void setup() {
        user = UUID.randomUUID();
        firstTenant = UUID.randomUUID();
        secondTenant = UUID.randomUUID();
        db.update("insert into tenants(id,name,slug,timezone) values (?,?,?,?)", firstTenant, "Moda Berber", "moda-berber-" + firstTenant, "Istanbul");
        db.update("insert into tenants(id,name,slug,timezone) values (?,?,?,?)", secondTenant, "Kadıköy Makas", "kadikoy-makas-" + secondTenant, "Europe/Istanbul");
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)", user, user + "@example.test", "unused", "Test User");
    }

    @Test
    void returnsOnlyTheTenantBoundToTheAccessTokenForEveryMemberRole() throws Exception {
        for (String role : java.util.List.of("OWNER", "MANAGER", "STAFF")) {
            var response = get(jwt.generateAccessToken(user, firstTenant, role));
            assertEquals(200, response.statusCode(), response.body());
            var body = json.readTree(response.body());
            assertEquals(firstTenant.toString(), body.get("tenantId").asString());
            assertEquals("Moda Berber", body.get("tenantName").asString());
            assertEquals("moda-berber-" + firstTenant, body.get("tenantSlug").asString());
            assertEquals("Europe/Istanbul", body.get("timezone").asString());
        }

        var other = json.readTree(get(jwt.generateAccessToken(user, secondTenant, "STAFF")).body());
        assertEquals(secondTenant.toString(), other.get("tenantId").asString());
        assertEquals("Kadıköy Makas", other.get("tenantName").asString());
    }

    @Test
    void rejectsMissingOrCustomerAudienceTokens() throws Exception {
        assertEquals(401, get(null).statusCode());
        assertEquals(401, get(jwt.generateCustomerAccessToken(user)).statusCode());
    }

    private HttpResponse<String> get(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/booking-link"));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
