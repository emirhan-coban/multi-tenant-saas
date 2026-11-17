package com.saas.backend.auth;

import com.saas.backend.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regresyon: giriş ve oturum yenileme, henüz işletme bağlamı yokken `tenants` satırını (RLS)
 * okuyabilmelidir. Aksi halde işletme adı yüklenirken EntityNotFoundException oluşur ve
 * istek 401 görünümünde sonlanır. Testler gerçek HTTP ve kısıtlı `saas_app` rolüyle çalışır.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaffLoginTenantContextTests extends PostgresTestBase {
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    @Value("${local.server.port}") int port;

    @Test
    void loginAndRefreshReturnTenantNameWithoutPriorTenantContext() throws Exception {
        String email = "login-" + UUID.randomUUID() + "@test.example";
        var register = post("/api/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"Gizli1234\",\"fullName\":\"Ada Sahip\",\"tenantName\":\"Login Berber\"}");
        assertEquals(201, register.statusCode(), register.body());

        var login = post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"Gizli1234\"}");
        assertEquals(200, login.statusCode(), login.body());
        var body = json.readTree(login.body());
        assertEquals("Login Berber", body.get("memberships").get(0).get("tenantName").asString());

        var refresh = post("/api/auth/refresh", "{\"refreshToken\":\"" + body.get("refreshToken").asString() + "\"}");
        assertEquals(200, refresh.statusCode(), refresh.body());
        assertEquals("Login Berber", json.readTree(refresh.body()).get("memberships").get(0).get("tenantName").asString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
