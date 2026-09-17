package com.saas.backend.security;

import com.saas.backend.account.AccountMailer;
import com.saas.backend.operations.GmailSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Testlerde gerçek posta gönderilmez; bunun yerine e-postaya konulacak token kaydedilir.
 * Böylece uçtan uca akış (istek → e-postadaki bağlantı → sıfırlama) gerçekten denenebilir.
 */
public class RecordingAccountMailer extends AccountMailer {
    public record Sent(String recipient, String token, boolean customer, String purpose) {
    }

    private final List<Sent> sent = new ArrayList<>();

    public RecordingAccountMailer(GmailSender mail) {
        super(mail, "http://localhost:5173");
    }

    @Override
    public void passwordReset(String recipient, String token, boolean customer) {
        sent.add(new Sent(recipient, token, customer, "PASSWORD_RESET"));
    }

    @Override
    public void emailVerification(String recipient, String token, boolean customer) {
        sent.add(new Sent(recipient, token, customer, "EMAIL_VERIFY"));
    }

    public List<Sent> sent() {
        return sent;
    }

    public Sent last() {
        if (sent.isEmpty()) throw new AssertionError("Hiç e-posta gönderilmedi");
        return sent.getLast();
    }

    public void clear() {
        sent.clear();
    }
}
