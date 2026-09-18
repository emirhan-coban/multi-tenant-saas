package com.saas.backend.account;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JdbcAccountMailQueueTests {

    @Test
    void storesOnlyCiphertextAndTokenHash() {
        Object[][] captured = new Object[1][];
        var db = new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                captured[0] = args;
                return 1;
            }
        };
        var cipher = new AccountMailCipher(Base64.getEncoder().encodeToString(new byte[32]), "unused");
        var queue = new JdbcAccountMailQueue(db, cipher);

        queue.enqueue("customer@example.test", "Şifre sıfırlama", "body-with-secret-token", "secret-token");

        assertNotNull(captured[0]);
        assertEquals("customer@example.test", cipher.decrypt((String) captured[0][0]));
        assertEquals("Şifre sıfırlama", cipher.decrypt((String) captured[0][1]));
        assertEquals("body-with-secret-token", cipher.decrypt((String) captured[0][2]));
        assertEquals(AccountTokenService.hash("secret-token"), captured[0][3]);
        for (Object value : captured[0]) {
            assertFalse(value.toString().contains("customer@example.test"));
            assertFalse(value.toString().contains("secret-token"));
        }
    }
}
