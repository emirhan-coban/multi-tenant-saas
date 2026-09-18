package com.saas.backend.auth;

import com.saas.backend.PostgresTestBase;
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
class AuthTokenIntegrationTests extends PostgresTestBase {
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    // Faz 9: fixture SQL'i şema sahibiyle çalışır; uygulama kodu kısıtlı saas_app rolündedir.
    final JdbcTemplate jdbc = ownerJdbc();
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private UUID userId;
    private UUID tenantId;

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        jdbc.update("insert into tenants(id,name,slug,timezone) values (?,?,?,?)", tenantId, "Auth QA", tenantId.toString(), "Europe/Istanbul");
        jdbc.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)", userId, userId + "@example.test", "unused", "Auth QA");
    }

    @Test
    void protectedApiAcceptsOnlyAccessTokens() throws Exception {
        var accessResponse = getMe(jwt.generateAccessToken(userId, tenantId, "OWNER"));
        assertEquals(200, accessResponse.statusCode());

        var refreshResponse = getMe(jwt.generateRefreshToken(userId, tenantId, "OWNER"));
        assertEquals(401, refreshResponse.statusCode());
        assertEquals("UNAUTHORIZED", json.readTree(refreshResponse.body()).get("code").asString());
    }

    @Test
    void refreshEndpointRejectsAccessAndMalformedTokensWithStableError() throws Exception {
        var accessToken = jwt.generateAccessToken(userId, tenantId, "OWNER");
        for (String token : java.util.List.of(accessToken, "not-a-jwt")) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/refresh"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + token + "\"}"))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
            assertEquals("INVALID_REFRESH_TOKEN", json.readTree(response.body()).get("code").asString());
        }
    }

    @Test
    void logoutAllRevokesExistingAccessAndRefreshTokens() throws Exception {
        var accessToken = jwt.generateAccessToken(userId, tenantId, "OWNER");
        var refreshToken = jwt.generateRefreshToken(userId, tenantId, "OWNER");

        var logout = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/logout-all"))
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertEquals(204, http.send(logout, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, getMe(accessToken).statusCode());

        var refresh = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/refresh"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .build();
        var refreshResponse = http.send(refresh, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, refreshResponse.statusCode());
        assertEquals("INVALID_REFRESH_TOKEN", json.readTree(refreshResponse.body()).get("code").asString());
    }

    private HttpResponse<String> getMe(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
