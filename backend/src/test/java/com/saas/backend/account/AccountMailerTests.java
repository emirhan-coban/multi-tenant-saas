package com.saas.backend.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(OutputCaptureExtension.class)
class AccountMailerTests {

    @Test
    void queueFailureNeverWritesRecipientOrTokenToLogs(CapturedOutput output) {
        AccountMailQueue failingQueue = (recipient, subject, body, token) -> {
            throw new IllegalStateException("queue unavailable");
        };
        var mailer = new AccountMailer(failingQueue, "https://randevu.example");
        String recipient = "private-customer@example.test";
        String token = "very-secret-account-token";

        assertFalse(mailer.passwordReset(recipient, token, true));

        assertFalse(output.getAll().contains(recipient));
        assertFalse(output.getAll().contains(token));
        assertFalse(output.getAll().contains("/musteri/sifre-sifirla"));
    }
}
