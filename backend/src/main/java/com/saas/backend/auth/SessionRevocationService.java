package com.saas.backend.auth;

import io.jsonwebtoken.Claims;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * "Tüm cihazlardan çıkış": bu andan (saniye hassasiyetinde) önce üretilmiş refresh token'lar reddedilir ve hesabın
 * push abonelikleri kapatılır. Access token'lar kısa ömürlüdür (app.jwt.expiration-minutes) ve süresi dolunca yenilenemez.
 */
@Service
public class SessionRevocationService {
    private final JdbcTemplate db;

    public SessionRevocationService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public void revokeStaff(UUID userId) {
        db.update("update users set session_version=session_version+1, sessions_valid_after=now() where id=?", userId);
        db.update("update push_subscriptions set disabled_at=now(),disabled_reason='LOGOUT_ALL' where owner_type='STAFF' and owner_id=? and disabled_at is null", userId);
    }

    @Transactional
    public void revokeCustomer(UUID accountId) {
        db.update("update customer_accounts set session_version=session_version+1, sessions_valid_after=now() where id=?", accountId);
        db.update("update push_subscriptions set disabled_at=now(),disabled_reason='LOGOUT_ALL' where owner_type='CUSTOMER' and owner_id=? and disabled_at is null", accountId);
    }

    public boolean staffTokenRevoked(UUID userId, Claims claims) {
        return revoked(db.queryForList("select session_version,sessions_valid_after from users where id=?", userId), claims);
    }

    public boolean customerTokenRevoked(UUID accountId, Claims claims) {
        return revoked(db.queryForList("select session_version,sessions_valid_after from customer_accounts where id=?", accountId), claims);
    }

    public long staffSessionVersion(UUID userId) {
        return sessionVersion("users", userId);
    }

    public long customerSessionVersion(UUID accountId) {
        return sessionVersion("customer_accounts", accountId);
    }

    public boolean hasActiveMembership(UUID userId, UUID tenantId) {
        Integer count = db.queryForObject(
                "select count(*) from memberships where user_id=? and tenant_id=? and active",
                Integer.class, userId, tenantId);
        return count != null && count > 0;
    }

    private long sessionVersion(String table, UUID id) {
        Long version = db.queryForObject("select session_version from " + table + " where id=?", Long.class, id);
        if (version == null) throw new IllegalStateException("Account session version missing");
        return version;
    }

    private static boolean revoked(java.util.List<java.util.Map<String, Object>> rows, Claims claims) {
        if (rows.isEmpty()) return true;
        Number current = (Number) rows.getFirst().get("session_version");
        Number token = claims.get("sessionVersion", Number.class);
        if (token != null) return current == null || token.longValue() != current.longValue();
        Timestamp validAfter = (Timestamp) rows.getFirst().get("sessions_valid_after");
        return validAfter != null && (claims.getIssuedAt() == null || claims.getIssuedAt().toInstant().isBefore(validAfter.toInstant()));
    }
}
