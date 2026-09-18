package com.saas.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Faz 9 — yayın öncesi güvenlik ayarları.
 *
 * @param allowedOrigins   CORS'a izin verilen tam kaynaklar (şema + host + port). Joker kabul edilmez.
 * @param contentSecurityPolicy API yanıtları için CSP; tarayıcıya sunulan tek içerik JSON olduğundan sıkıdır.
 * @param hstsMaxAgeSeconds HSTS süresi; 0 verilirse başlık eklenmez (yerel http geliştirme).
 * @param devSecretsAllowed true ise geliştirme varsayılanları (örnek JWT anahtarı, boş ops token) kabul edilir.
 *                          Üretimde APP_SECURITY_DEV_SECRETS_ALLOWED=false olmalıdır.
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        List<String> allowedOrigins,
        String contentSecurityPolicy,
        long hstsMaxAgeSeconds,
        boolean devSecretsAllowed
) {
    public SecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        contentSecurityPolicy = contentSecurityPolicy == null || contentSecurityPolicy.isBlank()
                ? "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
                : contentSecurityPolicy;
    }
}
