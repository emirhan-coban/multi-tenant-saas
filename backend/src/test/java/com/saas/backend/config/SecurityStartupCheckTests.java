package com.saas.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityStartupCheckTests {
    private static final String STRONG_SECRET = "production-secret-that-is-longer-than-32-bytes";
    private static final String MAIL_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void productionReadyEmailConfigurationPassesMailChecks() {
        var check = check(true, "live", "sender@example.test", "app-password");

        assertFalse(check.problems().stream().anyMatch(problem -> problem.contains("MAIL_") || problem.contains("GMAIL_")));
    }

    @Test
    void disabledTestModeOrMissingCredentialsAreReported() {
        var problems = check(false, "test", "", "").problems();

        assertTrue(problems.stream().anyMatch(problem -> problem.contains("MAIL_ENABLED=true")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("MAIL_DELIVERY_MODE=live")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("GMAIL_ADDRESS")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("GMAIL_APP_PASSWORD")));
    }

    @Test
    void invalidAccountMailEncryptionKeyIsReported() {
        var db = restrictedDatabase();
        var security = new SecurityProperties(List.of("https://randevu.example"), "default-src 'none'", 31536000, false);
        var check = new SecurityStartupCheck(security, db, STRONG_SECRET, "ops-token", "vapid-key", false,
                true, "live", "sender@example.test", "app-password", "too-short");

        assertTrue(check.problems().stream().anyMatch(problem -> problem.contains("APP_ACCOUNT_MAIL_KEY")));
    }

    private SecurityStartupCheck check(boolean enabled, String mode, String address, String password) {
        var security = new SecurityProperties(List.of("https://randevu.example"), "default-src 'none'", 31536000, false);
        return new SecurityStartupCheck(security, restrictedDatabase(), STRONG_SECRET, "ops-token", "vapid-key", false,
                enabled, mode, address, password, MAIL_KEY);
    }

    private JdbcTemplate restrictedDatabase() {
        return new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                return requiredType.cast("restricted");
            }
        };
    }
}
