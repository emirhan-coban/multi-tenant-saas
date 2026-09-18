package com.saas.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Faz 9 — yayın öncesi yapılandırma denetimi.
 *
 * <p>{@code app.security.dev-secrets-allowed=false} (üretim) olduğunda eksik veya zayıf ayarlarla
 * açılışı engeller. Geliştirmede aynı bulgular yalnızca uyarı olarak günlüğe yazılır, böylece
 * yerel çalıştırma bozulmaz ama sorun görünür olur.</p>
 */
@Component
public class SecurityStartupCheck {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityStartupCheck.class);
    /** Depoya işlenmiş örnek anahtar: üretimde kullanılması yasak. */
    static final String DEV_JWT_SECRET = "12342222233232325678987654321213324355232323122321";

    private final SecurityProperties security;
    private final JdbcTemplate db;
    private final String jwtSecret;
    private final String opsToken;
    private final String vapidPrivateKey;
    private final boolean ephemeralPushKeys;

    public SecurityStartupCheck(SecurityProperties security, JdbcTemplate db,
                                @Value("${app.jwt.secret}") String jwtSecret,
                                @Value("${app.ops.metrics-token:}") String opsToken,
                                @Value("${app.push.vapid-private-key:}") String vapidPrivateKey,
                                @Value("${app.push.allow-ephemeral-keys:true}") boolean ephemeralPushKeys) {
        this.security = security;
        this.db = db;
        this.jwtSecret = jwtSecret;
        this.opsToken = opsToken;
        this.vapidPrivateKey = vapidPrivateKey;
        this.ephemeralPushKeys = ephemeralPushKeys;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        List<String> problems = problems();
        if (problems.isEmpty()) {
            LOG.info("Güvenlik denetimi tamam: cors={} dbRole={}", security.allowedOrigins(), databaseRole());
            return;
        }
        if (security.devSecretsAllowed()) {
            problems.forEach(problem -> LOG.warn("Güvenlik uyarısı (geliştirme): {}", problem));
            return;
        }
        throw new IllegalStateException("Üretim güvenlik denetimi başarısız: " + String.join(" | ", problems));
    }

    /** Denetim bulguları; testler doğrudan çağırabilir. */
    List<String> problems() {
        var problems = new ArrayList<String>();
        if (jwtSecret == null || jwtSecret.isBlank() || DEV_JWT_SECRET.equals(jwtSecret))
            problems.add("APP_JWT_SECRET ayarlanmamış veya depodaki örnek anahtar kullanılıyor");
        else if (jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
            problems.add("APP_JWT_SECRET en az 32 bayt olmalı (HS256)");
        if (security.allowedOrigins().isEmpty())
            problems.add("APP_ALLOWED_ORIGINS boş: CORS kaynakları açıkça verilmeli");
        security.allowedOrigins().stream()
                .filter(origin -> origin.contains("*"))
                .forEach(origin -> problems.add("CORS kaynağında joker kullanılamaz: " + origin));
        security.allowedOrigins().stream()
                .filter(origin -> !origin.startsWith("https://"))
                .forEach(origin -> problems.add("CORS kaynağı https olmalı: " + origin));
        if (opsToken == null || opsToken.isBlank())
            problems.add("APP_OPS_METRICS_TOKEN boş: /actuator/metrics korumasız kalır");
        if (vapidPrivateKey == null || vapidPrivateKey.isBlank())
            problems.add("APP_PUSH_VAPID_PRIVATE_KEY boş: bildirim anahtarları kalıcı olmalı");
        if (ephemeralPushKeys)
            problems.add("APP_PUSH_ALLOW_EPHEMERAL_KEYS=false olmalı");
        if (security.hstsMaxAgeSeconds() <= 0)
            problems.add("APP_HSTS_MAX_AGE_SECONDS pozitif olmalı");
        String privilege = databasePrivilege();
        if (privilege != null && !"restricted".equals(privilege))
            problems.add("Veritabanı rolü RLS'i atlıyor (" + privilege + "): süper kullanıcı olmayan bir rol kullanın");
        return problems;
    }

    private String databasePrivilege() {
        try {
            return db.queryForObject("""
                    select case when rolsuper or rolbypassrls then 'superuser/bypassrls' else 'restricted' end
                    from pg_roles where rolname = current_user
                    """, String.class);
        } catch (RuntimeException ex) {
            LOG.warn("Veritabanı rol denetimi yapılamadı: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private String databaseRole() {
        try {
            return db.queryForObject("select current_user || ' (' || (select case when rolsuper or rolbypassrls then 'RLS atlıyor' else 'RLS uygulanıyor' end from pg_roles where rolname = current_user) || ')'", String.class);
        } catch (RuntimeException ex) {
            return "bilinmiyor";
        }
    }
}
