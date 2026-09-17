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
        db.update("update users set sessions_valid_after=date_trunc('second', now()) where id=?", userId);
        db.update("update push_subscriptions set disabled_at=now(),disabled_reason='LOGOUT_ALL' where owner_type='STAFF' and owner_id=? and disabled_at is null", userId);
    }

    @Transactional
    public void revokeCustomer(UUID accountId) {
        db.update("update customer_accounts set sessions_valid_after=date_trunc('second', now()) where id=?", accountId);
        db.update("update push_subscriptions set disabled_at=now(),disabled_reason='LOGOUT_ALL' where owner_type='CUSTOMER' and owner_id=? and disabled_at is null", accountId);
    }

    public boolean staffTokenRevoked(UUID userId, Claims claims) {
        return revoked(db.queryForList("select sessions_valid_after from users where id=?", Timestamp.class, userId), claims);
    }

    public boolean customerTokenRevoked(UUID accountId, Claims claims) {
        return revoked(db.queryForList("select sessions_valid_after from customer_accounts where id=?", Timestamp.class, accountId), claims);
    }

    private static boolean revoked(java.util.List<Timestamp> rows, Claims claims) {
        if (rows.isEmpty() || rows.getFirst() == null) return false;
        return claims.getIssuedAt() == null || claims.getIssuedAt().toInstant().isBefore(rows.getFirst().toInstant());
    }
}
