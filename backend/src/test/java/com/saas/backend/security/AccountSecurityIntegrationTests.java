package com.saas.backend.security;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.account.AccountMailer;
import com.saas.backend.operations.GmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Faz 9 — şifre sıfırlama, e-posta doğrulama ve KVKK akışları, gerçek HTTP üzerinden.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AccountSecurityIntegrationTests.MailerConfig.class)
class AccountSecurityIntegrationTests extends PostgresTestBase {

    @TestConfiguration
    static class MailerConfig {
        @Bean
        @Primary
        AccountMailer recordingMailer(GmailSender mail) {
            return new RecordingAccountMailer(mail);
        }
    }

    @Autowired AccountMailer mailer;
    @Autowired JdbcTemplate application;
    final JdbcTemplate owner = ownerJdbc();
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();

    RecordingAccountMailer sentMail;
    String customerEmail;
    UUID customerId;

    @BeforeEach
    void setup() {
        sentMail = (RecordingAccountMailer) mailer;
        sentMail.clear();
        customerEmail = "faz9-" + UUID.randomUUID() + "@example.test";
        var response = post("/api/customer/v1/auth/register", """
                {"email":"%s","password":"Baslangic1","fullName":"Faz Dokuz","phone":""}
                """.formatted(customerEmail), null);
        assertEquals(201, response.statusCode(), response.body());
        customerId = owner.queryForObject("select id from customer_accounts where email=?", UUID.class, customerEmail);
    }

    // ------------------------------------------------------------------ şifre sıfırlama

    @Test
    void unknownEmailGetsTheSameAnswerAndCreatesNoToken() {
        var response = post("/api/customer/v1/auth/password/forgot",
                "{\"email\":\"yok-" + UUID.randomUUID() + "@example.test\"}", null);
        assertEquals(202, response.statusCode(), response.body());
        assertTrue(sentMail.sent().isEmpty(), "Bilinmeyen adrese e-posta gönderilmemeli");
    }

    @Test
    void resetLinkChangesThePasswordClosesSessionsAndCannotBeReused() {
        var session = login(customerEmail, "Baslangic1");
        String refreshToken = field(session, "refreshToken");
        // Oturum iptali saniye hassasiyetindedir (JWT "iat" saniye taşır): aynı saniyede
        // üretilen token'lar bilinçli olarak korunur, böylece sıfırlamadan hemen sonra
        // yapılan taze giriş düşmez. Test bu sınırı aşmak için bir saniye bekler.
        sleepOneSecond();

        assertEquals(202, post("/api/customer/v1/auth/password/forgot",
                "{\"email\":\"" + customerEmail + "\"}", null).statusCode());
        String token = sentMail.last().token();
        assertEquals("PASSWORD_RESET", sentMail.last().purpose());
        // Token düz metin olarak saklanmaz.
        assertEquals(0L, owner.queryForObject("select count(*) from account_tokens where token_hash = ?", Long.class, token));

        // Zayıf şifre reddedilir, token harcanmaz.
        assertEquals(400, post("/api/customer/v1/auth/password/reset",
                "{\"token\":\"" + token + "\",\"password\":\"parolaparola\"}", null).statusCode());

        assertEquals(204, post("/api/customer/v1/auth/password/reset",
                "{\"token\":\"" + token + "\",\"password\":\"YeniSifre9\"}", null).statusCode());

        // Eski oturum düşer, eski şifre çalışmaz, yeni şifre çalışır.
        assertEquals(401, post("/api/customer/v1/auth/refresh",
                "{\"refreshToken\":\"" + refreshToken + "\"}", null).statusCode());
        assertEquals(401, post("/api/customer/v1/auth/login",
                "{\"email\":\"" + customerEmail + "\",\"password\":\"Baslangic1\"}", null).statusCode());
        assertEquals(200, post("/api/customer/v1/auth/login",
                "{\"email\":\"" + customerEmail + "\",\"password\":\"YeniSifre9\"}", null).statusCode());

        // Aynı bağlantı ikinci kez kullanılamaz.
        assertEquals(400, post("/api/customer/v1/auth/password/reset",
                "{\"token\":\"" + token + "\",\"password\":\"UcuncuSifre9\"}", null).statusCode());

        // Sıfırlama e-posta sahipliğini kanıtlar.
        assertNotNull(owner.queryForObject("select email_verified_at from customer_accounts where id=?",
                java.sql.Timestamp.class, customerId));
    }

    @Test
    void newRequestInvalidatesThePreviousLinkAndRepeatedRequestsAreThrottled() {
        post("/api/customer/v1/auth/password/forgot", "{\"email\":\"" + customerEmail + "\"}", null);
        String first = sentMail.last().token();
        post("/api/customer/v1/auth/password/forgot", "{\"email\":\"" + customerEmail + "\"}", null);
        String second = sentMail.last().token();
        assertNotEquals(first, second);
        assertEquals(400, post("/api/customer/v1/auth/password/reset",
                "{\"token\":\"" + first + "\",\"password\":\"YeniSifre9\"}", null).statusCode(),
                "Yeni istek önceki bağlantıyı geçersiz kılmalı");

        for (int attempt = 0; attempt < 3; attempt++)
            post("/api/customer/v1/auth/password/forgot", "{\"email\":\"" + customerEmail + "\"}", null);
        var throttled = post("/api/customer/v1/auth/password/forgot", "{\"email\":\"" + customerEmail + "\"}", null);
        assertEquals(429, throttled.statusCode(), throttled.body());
        assertTrue(throttled.body().contains("TOO_MANY_REQUESTS"), throttled.body());
    }

    // ------------------------------------------------------------------ e-posta doğrulama

    @Test
    void verificationLinkMarksTheAddressAndRequiresASession() {
        assertEquals(401, post("/api/customer/v1/auth/email/verification", "", null).statusCode(),
                "Doğrulama e-postası istemek oturum gerektirir");

        String accessToken = field(login(customerEmail, "Baslangic1"), "accessToken");
        assertEquals(202, post("/api/customer/v1/auth/email/verification", "", accessToken).statusCode());
        assertEquals("EMAIL_VERIFY", sentMail.last().purpose());

        assertEquals(204, post("/api/customer/v1/auth/email/verify",
                "{\"token\":\"" + sentMail.last().token() + "\"}", null).statusCode());
        assertTrue(get("/api/customer/v1/auth/email/status", accessToken).body().contains("true"));

        // Zaten doğrulanmış hesap yeniden bağlantı istemez.
        var repeat = post("/api/customer/v1/auth/email/verification", "", accessToken);
        assertEquals(400, repeat.statusCode());
        assertTrue(repeat.body().contains("EMAIL_ALREADY_VERIFIED"), repeat.body());
    }

    // ------------------------------------------------------------------ KVKK

    @Test
    void registrationRecordsConsentAndCustomerCanExportThenDeleteTheirData() {
        assertNotNull(owner.queryForObject("select consent_version from customer_accounts where id=?",
                String.class, customerId), "Kayıt sırasında açık rıza sürümü işlenmeli");

        String accessToken = field(login(customerEmail, "Baslangic1"), "accessToken");
        var export = get("/api/customer/v1/account/export", accessToken);
        assertEquals(200, export.statusCode(), export.body());
        assertTrue(export.body().contains(customerEmail), "Dışa aktarma kendi verisini içermeli");
        assertTrue(export.body().contains("randevular"));

        // Yanlış onay metni silmez.
        assertEquals(400, post("/api/customer/v1/account/delete", "{\"confirmation\":\"olur\"}", accessToken).statusCode());
        assertEquals(204, post("/api/customer/v1/account/delete", "{\"confirmation\":\"HESABIMI SIL\"}", accessToken).statusCode());

        var row = owner.queryForMap("select email, full_name, phone, active, anonymized_at from customer_accounts where id=?", customerId);
        assertNotEquals(customerEmail, row.get("email"));
        assertEquals("Silinmiş kullanıcı", row.get("full_name"));
        assertNull(row.get("phone"));
        assertEquals(false, row.get("active"));
        assertNotNull(row.get("anonymized_at"));
        assertEquals(401, post("/api/customer/v1/auth/login",
                "{\"email\":\"" + customerEmail + "\",\"password\":\"Baslangic1\"}", null).statusCode());
        assertEquals(1L, owner.queryForObject(
                "select count(*) from kvkk_requests where subject_id=? and action='DELETE'", Long.class, customerId));
    }

    @Test
    void policyVersionIsReadableWithoutASession() {
        var response = get("/api/public/kvkk/policy", null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("version"), response.body());
    }

    // ------------------------------------------------------------------ yardımcılar

    private static void sleepOneSecond() {
        try { Thread.sleep(1100); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private String login(String email, String password) {
        var response = post("/api/customer/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static String field(String json, String name) {
        int start = json.indexOf("\"" + name + "\":\"") + name.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }

    private HttpResponse<String> post(String path, String body, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return send(builder.build());
    }

    private HttpResponse<String> get(String path, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return send(builder.build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
