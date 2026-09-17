package com.saas.backend.push;

import com.saas.backend.auth.ApiException;
import com.saas.backend.flags.FeatureFlag;
import com.saas.backend.flags.FeatureFlagService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PushSubscriptionService {
    static final int MAX_ACTIVE_PER_OWNER = 10;
    private final JdbcTemplate db;
    private final PushEndpointPolicy policy;
    private final WebPushConfiguration.WebPushKeys keys;
    private final FeatureFlagService flags;

    public PushSubscriptionService(JdbcTemplate db, PushEndpointPolicy policy, WebPushConfiguration.WebPushKeys keys, FeatureFlagService flags) {
        this.db = db;
        this.policy = policy;
        this.keys = keys;
        this.flags = flags;
    }

    @Transactional
    public void register(OwnerType ownerType, UUID ownerId, UUID tenantId, Request request, String userAgent) {
        if (!keys.configured()) throw new ApiException("PUSH_NOT_CONFIGURED", "Anlık bildirimler bu sunucuda henüz yapılandırılmadı.");
        if (tenantId != null) flags.require(FeatureFlag.PUSH_NOTIFICATIONS, tenantId, "Anlık bildirimler bu işletmede kapalı.");
        if (request == null || request.keys() == null) throw invalid();
        var endpoint = policy.validate(request.endpoint()).toString();
        validateKeys(request.keys().p256dh(), request.keys().auth());
        String hash = sha256(endpoint);
        // Aynı tarayıcı aboneliği başka bir hesapla kullanılmışsa sahiplik son giriş yapan hesaba geçer.
        db.update("""
                insert into push_subscriptions(owner_type,owner_id,endpoint,endpoint_hash,p256dh,auth_secret,user_agent)
                values (?,?,?,?,?,?,?)
                on conflict (endpoint_hash) do update set owner_type=excluded.owner_type,owner_id=excluded.owner_id,
                  endpoint=excluded.endpoint,p256dh=excluded.p256dh,auth_secret=excluded.auth_secret,user_agent=excluded.user_agent,
                  updated_at=now(),failure_count=0,disabled_at=null,disabled_reason=null
                """, ownerType.name(), ownerId, endpoint, hash, request.keys().p256dh().trim(), request.keys().auth().trim(),
                truncate(userAgent, 200));
        db.update("""
                update push_subscriptions set disabled_at=now(),disabled_reason='OWNER_LIMIT'
                where id in (select id from push_subscriptions where owner_type=? and owner_id=? and disabled_at is null
                             order by updated_at desc offset ?)
                """, ownerType.name(), ownerId, MAX_ACTIVE_PER_OWNER);
    }

    @Transactional
    public void unregister(OwnerType ownerType, UUID ownerId, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw invalid();
        db.update("""
                update push_subscriptions set disabled_at=coalesce(disabled_at,now()),disabled_reason=coalesce(disabled_reason,'UNSUBSCRIBED')
                where endpoint_hash=? and owner_type=? and owner_id=?
                """, sha256(endpoint.trim()), ownerType.name(), ownerId);
    }

    @Transactional(readOnly = true)
    public long activeCount(OwnerType ownerType, UUID ownerId) {
        return db.queryForObject("select count(*) from push_subscriptions where owner_type=? and owner_id=? and disabled_at is null",
                Long.class, ownerType.name(), ownerId);
    }

    private static void validateKeys(String p256dh, String auth) {
        try {
            WebPushCrypto.publicKey(WebPushCrypto.decode(p256dh));
            if (WebPushCrypto.decode(auth).length != 16) throw invalid();
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static ApiException invalid() {
        return new ApiException("INVALID_PUSH_SUBSCRIPTION", "Bildirim aboneliği geçersiz.");
    }

    public enum OwnerType { STAFF, CUSTOMER }
    public record Keys(String p256dh, String auth) {}
    public record Request(String endpoint, Keys keys) {}
}
