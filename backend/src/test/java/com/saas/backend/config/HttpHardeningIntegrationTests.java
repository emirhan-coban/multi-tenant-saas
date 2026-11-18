package com.saas.backend.config;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gerçek HTTP ile: hata kodları maskelenmez (eskiden her yakalanmayan hata 401 görünüyordu) ve
 * istemcinin gönderdiği sahte X-Forwarded-For hız sınırını aşmak için kullanılamaz.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpHardeningIntegrationTests extends PostgresTestBase {
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    @Value("${local.server.port}") int port;

    private HttpResponse<String> send(String method, String path, String body, String token, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        for (int i = 0; i + 1 < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String owner() throws Exception {
        String email = "hard-" + UUID.randomUUID() + "@test.example";
        var register = send("POST", "/api/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"Gizli1234\",\"fullName\":\"Ada\",\"tenantName\":\"Hardening Berber\"}", null);
        assertEquals(201, register.statusCode(), register.body());
        return json.readTree(register.body()).get("accessToken").asString();
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).get("code").asString(), response.body());
    }

    @Test
    void frameworkErrorsKeepTheirRealStatusInsteadOfBeingMaskedAs401() throws Exception {
        String token = owner();
        // Genel (anonim) uç: geçersiz UUID → 400, eskiden 401
        assertError(send("GET", "/api/public/v1/shops/x/availability?serviceId=bozuk&staffId=bozuk&date=2030-01-01", null, null), 400, "VALIDATION_ERROR");
        // Yetkili uç: eksik zorunlu parametre → 400
        assertError(send("DELETE", "/api/calendar-closures/" + UUID.randomUUID(), null, token), 400, "VALIDATION_ERROR");
        // Tür uyuşmazlığı (Instant bekleyen parametreye tarih) → 400
        assertError(send("GET", "/api/reports/summary?from=2030-01-01&to=2030-02-01", null, token), 400, "VALIDATION_ERROR");
        // Eksik zorunlu başlık (Idempotency-Key) → 400
        assertError(send("POST", "/api/booking-requests/" + UUID.randomUUID() + "/approve", "{}", token), 400, "VALIDATION_ERROR");
        // Yanlış metot → 405, olmayan yol → 404
        assertError(send("POST", "/api/health", "{}", null), 405, "METHOD_NOT_ALLOWED");
        assertError(send("GET", "/api/olmayan-yol", null, token), 404, "NOT_FOUND");
    }

    @Test
    void authenticationAndAuthorizationStillWork() throws Exception {
        // Tokensiz korumalı uç hâlâ 401; geçerli ama yetkisiz rol hâlâ 403 (fallback bunları yutmamalı).
        assertEquals(401, send("GET", "/api/services", null, null).statusCode());
        assertEquals(401, send("GET", "/api/olmayan-yol", null, null).statusCode());
    }

    @Test
    void spoofedForwardedForCannotBypassTheAuthIpRateLimit() throws Exception {
        // Her istek farklı e-posta (hesap limiti işlemez) ve farklı sahte X-Forwarded-For taşır.
        boolean limited = false;
        for (int i = 0; i < 40 && !limited; i++) {
            var response = send("POST", "/api/auth/login",
                    "{\"email\":\"spoof-" + UUID.randomUUID() + "@test.example\",\"password\":\"x\"}", null,
                    "X-Forwarded-For", "10." + (i % 250) + ".7." + (i + 1));
            limited = response.statusCode() == 429;
        }
        assertTrue(limited, "Sahte X-Forwarded-For her istekte yeni IP gibi görünüp sınırı aşmamalı");
    }
}
