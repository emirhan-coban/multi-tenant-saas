package com.saas.backend.account;

import com.saas.backend.auth.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Faz 9 — tek kullanımlık hesap token'ları (şifre sıfırlama, e-posta doğrulama).
 *
 * <p>Token yalnızca üretildiği anda düz metin olarak görülür; veritabanında SHA-256 özeti
 * saklanır. Böylece veritabanı okuma yetkisi olan biri geçerli bir bağlantı üretemez.
 * Aynı amaç için bir hesabın tek açık token'ı olabilir: yeni istek öncekini geçersiz kılar.</p>
 */
@Service
public class AccountTokenService {
    /** 256 bit entropi: kaba kuvvetle tahmin edilemez. */
    private static final int TOKEN_BYTES = 32;

    private final JdbcTemplate db;
    private final SecureRandom random = new SecureRandom();
    private final Duration passwordResetTtl;
    private final Duration emailVerifyTtl;
    private final int hourlyLimit;

    public AccountTokenService(JdbcTemplate db,
                               @Value("${app.account.password-reset-minutes:60}") long passwordResetMinutes,
                               @Value("${app.account.email-verify-hours:48}") long emailVerifyHours,
                               @Value("${app.account.hourly-request-limit:5}") int hourlyLimit) {
        this.db = db;
        this.passwordResetTtl = Duration.ofMinutes(passwordResetMinutes);
        this.emailVerifyTtl = Duration.ofHours(emailVerifyHours);
        this.hourlyLimit = hourlyLimit;
    }

    public enum Purpose {
        PASSWORD_RESET, EMAIL_VERIFY;

        public String subjectLine() {
            return this == PASSWORD_RESET ? "Şifre sıfırlama" : "E-posta doğrulama";
        }
    }

    public record Subject(String type, UUID id) {
        public static Subject staff(UUID id) { return new Subject("STAFF", id); }
        public static Subject customer(UUID id) { return new Subject("CUSTOMER", id); }
    }

    /** Yeni token üretir ve düz metnini döndürür; çağıran onu yalnızca e-postaya koyar. */
    @Transactional
    public String issue(Subject subject, Purpose purpose) {
        throttle(subject, purpose);
        // Açık token en fazla bir tane: önceki istek geçersizleşir.
        db.update("update account_tokens set used_at=now() where subject_type=? and subject_id=? and purpose=? and used_at is null",
                subject.type(), subject.id(), purpose.name());
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = Instant.now().plus(purpose == Purpose.PASSWORD_RESET ? passwordResetTtl : emailVerifyTtl);
        db.update("insert into account_tokens(subject_type,subject_id,purpose,token_hash,expires_at) values (?,?,?,?,?)",
                subject.type(), subject.id(), purpose.name(), hash(token), Timestamp.from(expires));
        return token;
    }

    /**
     * Token'ı doğrular ve aynı işlemde tüketir. Süresi dolmuş, kullanılmış veya
     * bilinmeyen token'lar aynı hatayı üretir: hangi durumda olduğu sızdırılmaz.
     */
    @Transactional
    public Subject consume(String token, Purpose purpose) {
        if (token == null || token.isBlank()) throw invalid();
        var rows = db.queryForList("""
                update account_tokens set used_at = now()
                where token_hash = ? and purpose = ? and used_at is null and expires_at > now()
                returning subject_type, subject_id
                """, hash(token), purpose.name());
        if (rows.isEmpty()) throw invalid();
        var row = rows.getFirst();
        return new Subject((String) row.get("subject_type"), (UUID) row.get("subject_id"));
    }

    /** Süresi geçmiş ve kullanılmış token'ları temizler (veri saklama turu çağırır). */
    @Transactional
    public int purge(int keepUsedDays) {
        return db.update("""
                delete from account_tokens
                where (used_at is null and expires_at < now() - interval '1 day')
                   or (used_at is not null and used_at < now() - (? * interval '1 day'))
                """, keepUsedDays);
    }

    private void throttle(Subject subject, Purpose purpose) {
        Long recent = db.queryForObject("""
                select count(*) from account_tokens
                where subject_type=? and subject_id=? and purpose=? and created_at > now() - interval '1 hour'
                """, Long.class, subject.type(), subject.id(), purpose.name());
        if (recent != null && recent >= hourlyLimit)
            throw new ApiException("TOO_MANY_REQUESTS", "Çok fazla istek gönderildi. Bir saat sonra tekrar deneyin.");
    }

    private static ApiException invalid() {
        return new ApiException("INVALID_ACCOUNT_TOKEN", "Bağlantı geçersiz veya süresi dolmuş. Yeniden isteyin.");
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 bulunamadı", ex);
        }
    }
}
