package com.saas.backend.account;

import com.saas.backend.PostgresTestBase;
import com.saas.backend.operations.GmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountEmailQueueIntegrationTests extends PostgresTestBase {
    final JdbcTemplate db = ownerJdbc();
    final HttpClient http = HttpClient.newHttpClient();
    @Autowired AccountEmailWorker worker;
    @Autowired AccountMailCipher cipher;
    @MockitoBean GmailSender gmail;
    @Value("${local.server.port}") int port;
    String email;

    @BeforeEach
    void setup() {
        email = "queue-" + UUID.randomUUID() + "@example.test";
        var registered = post("/api/customer/v1/auth/register", """
                {"email":"%s","password":"Baslangic1","fullName":"Queue Test","phone":""}
                """.formatted(email));
        assertEquals(201, registered.statusCode(), registered.body());
        when(gmail.ready()).thenReturn(true);
        when(gmail.allowed(anyString())).thenReturn(true);
    }

    @Test
    void queuedSecretsAreEncryptedAndDeliveredOnlyOnce() {
        assertEquals(202, forgot().statusCode());
        var row = latest();
        assertEquals("PENDING", row.get("status"));
        assertFalse(row.get("recipient_ciphertext").toString().contains(email));
        assertEquals(email, cipher.decrypt((String) row.get("recipient_ciphertext")));
        assertTrue(cipher.decrypt((String) row.get("body_ciphertext")).contains("/musteri/sifre-sifirla?token="));

        UUID id = (UUID) row.get("id");
        worker.deliver(id);
        worker.deliver(id);

        verify(gmail, times(1)).send(eq(id), eq(email), eq("Şifre sıfırlama bağlantısı"), contains("?token="));
        assertEquals("SENT", db.queryForObject("select status from account_email_outbox where id=?", String.class, id));
    }

    @Test
    void newerRequestSkipsThePreviousPendingMessage() {
        forgot();
        UUID first = (UUID) latest().get("id");
        forgot();
        UUID second = (UUID) latest().get("id");

        assertNotEquals(first, second);
        assertEquals("SKIPPED", db.queryForObject("select status from account_email_outbox where id=?", String.class, first));
        worker.deliver(first);
        worker.deliver(second);
        verify(gmail, times(1)).send(eq(second), eq(email), anyString(), anyString());
    }

    @Test
    void smtpFailureUsesBoundedRetriesThenFails() {
        forgot();
        UUID id = (UUID) latest().get("id");
        doThrow(new IllegalStateException("SMTP unavailable"))
                .when(gmail).send(eq(id), anyString(), anyString(), anyString());

        for (int attempt = 0; attempt < 5; attempt++) {
            db.update("update account_email_outbox set available_at=now() where id=?", id);
            worker.deliver(id);
        }

        var state = db.queryForMap("select status,attempts,last_error from account_email_outbox where id=?", id);
        assertEquals("FAILED", state.get("status"));
        assertEquals(5, state.get("attempts"));
        assertEquals("IllegalStateException", state.get("last_error"));
        verify(gmail, times(5)).send(eq(id), anyString(), anyString(), anyString());
    }

    private HttpResponse<String> forgot() {
        return post("/api/customer/v1/auth/password/forgot", "{\"email\":\"" + email + "\"}");
    }

    private java.util.Map<String, Object> latest() {
        return db.queryForMap("select * from account_email_outbox order by created_at desc,id desc limit 1");
    }

    private HttpResponse<String> post(String path, String body) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
