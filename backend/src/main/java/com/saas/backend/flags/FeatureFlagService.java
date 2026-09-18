package com.saas.backend.flags;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.operations.OperationsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Global varsayılan + işletme bazlı geçersiz kılma. Çözüm sırası:
 * global kapalıysa kapalı (acil durum anahtarı işletme tarafından açılamaz) → işletme satırı → global satır → kod varsayılanı.
 * Bayraklar her istekte okunur; kill switch değişikliği önbellek beklemeden anında etkili olur.
 */
@Service
public class FeatureFlagService {
    private final JdbcTemplate db;
    private final OperationsService operations;

    public FeatureFlagService(JdbcTemplate db, OperationsService operations) {
        this.db = db;
        this.operations = operations;
    }

    @Transactional(readOnly = true)
    public boolean enabled(FeatureFlag flag, UUID tenantId) {
        return resolve(tenantId).get(flag).enabled();
    }

    @Transactional(readOnly = true)
    public Map<FeatureFlag, State> resolve(UUID tenantId) {
        Map<FeatureFlag, Boolean> global = new EnumMap<>(FeatureFlag.class);
        Map<FeatureFlag, Boolean> tenant = new EnumMap<>(FeatureFlag.class);
        for (var row : db.queryForList("select flag_key,tenant_id,enabled from feature_flags where tenant_id is null or tenant_id=?", tenantId)) {
            var flag = FeatureFlag.fromKey((String) row.get("flag_key"));
            if (flag.isEmpty()) continue;
            if (row.get("tenant_id") == null) global.put(flag.get(), (Boolean) row.get("enabled"));
            else tenant.put(flag.get(), (Boolean) row.get("enabled"));
        }
        Map<FeatureFlag, State> result = new EnumMap<>(FeatureFlag.class);
        for (FeatureFlag flag : FeatureFlag.values()) {
            boolean globalValue = global.getOrDefault(flag, flag.defaultValue());
            Boolean override = flag.tenantOverridable() ? tenant.get(flag) : null;
            boolean effective = globalValue && (override == null || override);
            result.put(flag, new State(flag.key(), effective, globalValue, override));
        }
        return result;
    }

    @Transactional
    public Map<FeatureFlag, State> setTenantOverride(AuthenticatedUser user, String key, Boolean enabled, String reason) {
        var flag = FeatureFlag.fromKey(key).filter(FeatureFlag::tenantOverridable)
                .orElseThrow(() -> new ApiException("UNKNOWN_FEATURE_FLAG", "Bu özellik işletme tarafından değiştirilemez."));
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) throw new ApiException("FLAG_REASON_REQUIRED", "Değişiklik gerekçesi zorunludur.");
        if (trimmed.length() > 250) throw new ApiException("VALIDATION_ERROR", "Gerekçe en fazla 250 karakter olabilir.");
        var before = resolve(user.tenantId()).get(flag);
        if (enabled == null) {
            db.update("delete from feature_flags where flag_key=? and tenant_id=?", flag.key(), user.tenantId());
        } else {
            db.update("""
                    insert into feature_flags(flag_key,tenant_id,enabled,reason,updated_by,updated_at) values (?,?,?,?,?,now())
                    on conflict (flag_key, tenant_id) where tenant_id is not null
                    do update set enabled=excluded.enabled,reason=excluded.reason,updated_by=excluded.updated_by,updated_at=now()
                    """, flag.key(), user.tenantId(), enabled, trimmed, user.userId());
        }
        var after = resolve(user.tenantId());
        operations.audit(user, "FEATURE_FLAG_CHANGED", "tenant", user.tenantId(), Map.of(
                "flag", flag.key(), "before", before.enabled(), "after", after.get(flag).enabled(),
                "override", enabled == null ? "inherit" : enabled.toString(), "reason", trimmed));
        return after;
    }

    public void require(FeatureFlag flag, UUID tenantId, String message) {
        if (!enabled(flag, tenantId)) throw new ApiException("FEATURE_DISABLED", message, Map.of("flag", flag.key()));
    }

    public record State(String key, boolean enabled, boolean globalEnabled, Boolean tenantOverride) {}
}
