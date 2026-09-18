package com.saas.backend.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAccountMailQueue implements AccountMailQueue {
    private final JdbcTemplate db;
    private final AccountMailCipher cipher;

    public JdbcAccountMailQueue(JdbcTemplate db, AccountMailCipher cipher) {
        this.db = db;
        this.cipher = cipher;
    }

    @Override
    public void enqueue(String recipient, String subject, String body, String token) {
        int inserted = db.update("""
                insert into account_email_outbox(token_hash,recipient_ciphertext,subject_ciphertext,body_ciphertext)
                select token_hash,?,?,? from account_tokens
                where token_hash=? and used_at is null and expires_at>now()
                """, cipher.encrypt(recipient), cipher.encrypt(subject), cipher.encrypt(body), AccountTokenService.hash(token));
        if (inserted != 1) throw new IllegalStateException("Geçerli hesap token'ı için e-posta kuyruğu oluşturulamadı");
    }
}
