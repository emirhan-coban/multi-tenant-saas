package com.saas.backend.account;

import com.saas.backend.operations.GmailSender;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/** Bir hesap e-postasını kısa DB işlemleriyle sahiplenir, transaction dışında gönderir. */
@Service
public class AccountEmailWorker {
    private final JdbcTemplate db;
    private final GmailSender sender;
    private final AccountMailCipher cipher;
    private final TransactionTemplate transactions;

    public AccountEmailWorker(JdbcTemplate db, GmailSender sender, AccountMailCipher cipher,
                              TransactionTemplate transactions) {
        this.db = db;
        this.sender = sender;
        this.cipher = cipher;
        this.transactions = transactions;
    }

    public void deliver(UUID id) {
        Map<String, Object> row = transactions.execute(status -> claim(id));
        if (row == null) return;

        if (!sender.ready()) {
            transactions.executeWithoutResult(status -> db.update("""
                    update account_email_outbox set status='PENDING',claimed_at=null,
                      available_at=now()+interval '1 minute' where id=? and status='PROCESSING'
                    """, id));
            return;
        }

        String recipient;
        String subject;
        String body;
        try {
            recipient = cipher.decrypt((String) row.get("recipient_ciphertext"));
            subject = cipher.decrypt((String) row.get("subject_ciphertext"));
            body = cipher.decrypt((String) row.get("body_ciphertext"));
        } catch (RuntimeException ex) {
            finish(id, "FAILED", "DECRYPTION_FAILED");
            LoggerFactory.getLogger(getClass()).error("Account email decryption failed eventId={}", id);
            return;
        }

        if (!sender.allowed(recipient)) {
            finish(id, "SKIPPED", "RECIPIENT_NOT_ALLOWED");
            return;
        }

        // Yeni token talebi veya token kullanımı, sahiplenmeden sonra gerçekleşmiş olabilir.
        if (!stillDeliverable(id)) {
            finish(id, "SKIPPED", "TOKEN_INVALIDATED");
            return;
        }

        try {
            sender.send(id, recipient, subject, body);
            finish(id, "SENT", null);
        } catch (RuntimeException ex) {
            retry(id, ex.getClass().getSimpleName());
        }
    }

    public void recoverUncertainDeliveries() {
        int failed = db.update("""
                update account_email_outbox set status='FAILED',finished_at=now(),claimed_at=null,
                  last_error='DELIVERY_STATE_UNKNOWN'
                where status='PROCESSING' and claimed_at < now()-interval '5 minutes'
                """);
        if (failed > 0)
            LoggerFactory.getLogger(getClass()).error("Uncertain account email deliveries quarantined count={}", failed);
    }

    private Map<String, Object> claim(UUID id) {
        var rows = db.queryForList("""
                update account_email_outbox set status='PROCESSING',claimed_at=now()
                where id=? and status='PENDING' and available_at<=now()
                returning token_hash,recipient_ciphertext,subject_ciphertext,body_ciphertext
                """, id);
        if (rows.isEmpty()) return null;
        if (!tokenValid((String) rows.getFirst().get("token_hash"))) {
            db.update("update account_email_outbox set status='SKIPPED',finished_at=now(),claimed_at=null,last_error='TOKEN_INVALIDATED' where id=?", id);
            return null;
        }
        return rows.getFirst();
    }

    private boolean stillDeliverable(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("""
                select exists(
                  select 1 from account_email_outbox q join account_tokens t on t.token_hash=q.token_hash
                  where q.id=? and q.status='PROCESSING' and t.used_at is null and t.expires_at>now())
                """, Boolean.class, id));
    }

    private boolean tokenValid(String hash) {
        return Boolean.TRUE.equals(db.queryForObject("""
                select exists(select 1 from account_tokens where token_hash=? and used_at is null and expires_at>now())
                """, Boolean.class, hash));
    }

    private void retry(UUID id, String error) {
        transactions.executeWithoutResult(status -> {
            var rows = db.queryForList(
                    "select attempts+1 from account_email_outbox where id=? and status='PROCESSING'", Integer.class, id);
            if (rows.isEmpty()) return;
            Integer attempts = rows.getFirst();
            if (attempts >= 5) {
                db.update("""
                        update account_email_outbox set attempts=?,status='FAILED',finished_at=now(),
                          claimed_at=null,last_error=? where id=? and status='PROCESSING'
                        """, attempts, error, id);
            } else {
                db.update("""
                        update account_email_outbox set attempts=?,status='PENDING',claimed_at=null,
                          available_at=now()+(? * interval '1 minute'),last_error=?
                        where id=? and status='PROCESSING'
                        """, attempts, Math.min(60, 1 << attempts), error, id);
            }
        });
        LoggerFactory.getLogger(getClass()).warn("Account email delivery failed eventId={} error={}", id, error);
    }

    private void finish(UUID id, String status, String error) {
        transactions.executeWithoutResult(transaction -> db.update("""
                update account_email_outbox set status=?,finished_at=now(),claimed_at=null,last_error=?
                where id=? and status='PROCESSING'
                """, status, error, id));
    }
}
