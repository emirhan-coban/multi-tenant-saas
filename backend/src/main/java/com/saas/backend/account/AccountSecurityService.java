package com.saas.backend.account;

import com.saas.backend.auth.ApiException;
import com.saas.backend.auth.SessionRevocationService;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.saas.backend.operations.AuthRateLimitService;

import java.util.Locale;
import java.util.UUID;

/**
 * Faz 9 — şifre sıfırlama ve e-posta doğrulama akışları (hem işletme kullanıcıları hem müşteriler).
 *
 * <p>Tasarım kararları:</p>
 * <ul>
 *   <li><b>Hesap sayımına kapalı:</b> "şifremi unuttum" isteği, e-posta kayıtlı olsun olmasın
 *       aynı yanıtı verir ve aynı süreyi harcar.</li>
 *   <li><b>Sıfırlama tüm oturumları kapatır:</b> şifre değişince eski refresh token'lar ve
 *       push abonelikleri geçersizleşir; hesabı ele geçiren birinin oturumu da düşer.</li>
 *   <li><b>Sıfırlama e-postayı doğrular:</b> bağlantıyı kullanabilen kişi adrese erişebiliyordur.</li>
 * </ul>
 */
@Service
public class AccountSecurityService {
    private final JdbcTemplate db;
    private final AccountTokenService tokens;
    private final AccountMailer mailer;
    private final PasswordEncoder passwords;
    private final SessionRevocationService sessions;
    private final AuthRateLimitService authRateLimits;

    public AccountSecurityService(JdbcTemplate db, AccountTokenService tokens, AccountMailer mailer,
                                  PasswordEncoder passwords, SessionRevocationService sessions,
                                  AuthRateLimitService authRateLimits) {
        this.db = db;
        this.tokens = tokens;
        this.mailer = mailer;
        this.passwords = passwords;
        this.sessions = sessions;
        this.authRateLimits = authRateLimits;
    }

    // ------------------------------------------------------------------ şifre sıfırlama

    @Transactional
    public void requestPasswordReset(String email, boolean customer) {
        String normalized = normalize(email);
        authRateLimits.checkAccount(customer ? "customer-forgot" : "staff-forgot", normalized);
        UUID account = findAccount(normalized, customer);
        if (account == null) {
            // Hesap yok: aynı yanıt döner, hiçbir şey yapılmaz.
            LoggerFactory.getLogger(getClass()).info("Şifre sıfırlama isteği bilinmeyen hesap için geldi customer={}", customer);
            return;
        }
        String token = tokens.issue(subject(account, customer), AccountTokenService.Purpose.PASSWORD_RESET);
        if (!mailer.passwordReset(normalized, token, customer)) tokens.discard(token);
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        validatePassword(newPassword);
        var subject = tokens.consume(token, AccountTokenService.Purpose.PASSWORD_RESET);
        String hash = passwords.encode(newPassword);
        boolean customer = "CUSTOMER".equals(subject.type());
        int updated = db.update(customer
                        ? "update customer_accounts set password_hash=?, email_verified_at=coalesce(email_verified_at, now()) where id=?"
                        : "update users set password_hash=?, email_verified_at=coalesce(email_verified_at, now()) where id=?",
                hash, subject.id());
        if (updated == 0) throw new ApiException("INVALID_ACCOUNT_TOKEN", "Bağlantı geçersiz veya süresi dolmuş. Yeniden isteyin.");
        // Şifre değişti: açık tüm oturumlar kapatılır.
        if (customer) sessions.revokeCustomer(subject.id()); else sessions.revokeStaff(subject.id());
    }

    // ------------------------------------------------------------------ e-posta doğrulama

    @Transactional
    public void requestEmailVerification(UUID account, boolean customer) {
        var row = db.queryForList(customer
                        ? "select email, email_verified_at from customer_accounts where id=?"
                        : "select email, email_verified_at from users where id=?",
                account);
        if (row.isEmpty()) throw new ApiException("NOT_FOUND", "Hesap bulunamadı");
        if (row.getFirst().get("email_verified_at") != null)
            throw new ApiException("EMAIL_ALREADY_VERIFIED", "E-posta adresiniz zaten doğrulanmış");
        String token = tokens.issue(subject(account, customer), AccountTokenService.Purpose.EMAIL_VERIFY);
        if (!mailer.emailVerification((String) row.getFirst().get("email"), token, customer)) tokens.discard(token);
    }

    @Transactional
    public void verifyEmail(String token) {
        var subject = tokens.consume(token, AccountTokenService.Purpose.EMAIL_VERIFY);
        db.update("CUSTOMER".equals(subject.type())
                        ? "update customer_accounts set email_verified_at=coalesce(email_verified_at, now()) where id=?"
                        : "update users set email_verified_at=coalesce(email_verified_at, now()) where id=?",
                subject.id());
    }

    /** Hesap ekranlarında rozet olarak gösterilir; doğrulama zorunlu bir kapı değildir. */
    @Transactional(readOnly = true)
    public boolean emailVerified(UUID account, boolean customer) {
        var rows = db.queryForList(customer
                ? "select email_verified_at from customer_accounts where id=?"
                : "select email_verified_at from users where id=?", account);
        return !rows.isEmpty() && rows.getFirst().get("email_verified_at") != null;
    }

    // ------------------------------------------------------------------ yardımcılar

    private UUID findAccount(String email, boolean customer) {
        var rows = db.queryForList(customer
                ? "select id from customer_accounts where lower(email)=? and active"
                : "select id from users where lower(email)=?", UUID.class, email);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static AccountTokenService.Subject subject(UUID account, boolean customer) {
        return customer ? AccountTokenService.Subject.customer(account) : AccountTokenService.Subject.staff(account);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72)
            throw new ApiException("WEAK_PASSWORD", "Şifre en az 8, en fazla 72 karakter olmalı");
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean other = password.chars().anyMatch(character -> !Character.isLetter(character));
        if (!letter || !other)
            throw new ApiException("WEAK_PASSWORD", "Şifre hem harf hem rakam/işaret içermeli");
    }
}
