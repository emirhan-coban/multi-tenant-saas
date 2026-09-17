package com.saas.backend.push;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class WebPushConfiguration {

    /**
     * Üretimde {@code APP_PUSH_VAPID_PUBLIC_KEY} ve {@code APP_PUSH_VAPID_PRIVATE_KEY} verilmelidir; anahtar değişirse
     * mevcut abonelikler geçersizleşir. Anahtar yoksa ve {@code app.push.allow-ephemeral-keys=false} ise push kapalı kalır.
     */
    @Bean
    public WebPushKeys webPushKeys(@Value("${app.push.vapid-public-key:}") String publicKey,
                                   @Value("${app.push.vapid-private-key:}") String privateKey,
                                   @Value("${app.push.allow-ephemeral-keys:true}") boolean allowEphemeral,
                                   @Value("${app.push.subject:mailto:ops@localhost}") String subject) {
        var log = LoggerFactory.getLogger(WebPushConfiguration.class);
        if (!publicKey.isBlank() && !privateKey.isBlank()) {
            return new WebPushKeys(new WebPushCrypto(WebPushCrypto.keyPair(publicKey, privateKey)), subject, true, false);
        }
        if (!allowEphemeral) {
            log.warn("Web push kapalı: VAPID anahtarları tanımlı değil (app.push.vapid-public-key / vapid-private-key).");
            return new WebPushKeys(null, subject, false, false);
        }
        var generated = WebPushCrypto.generateKeyPair();
        var crypto = new WebPushCrypto(generated);
        // Özel anahtar hiçbir zaman loglanmaz. Kalıcı anahtar için: docs/phase-8-pwa-notifications-rollout.md
        log.warn("Web push geçici VAPID anahtarıyla çalışıyor; yeniden başlatmada abonelikler geçersizleşir (yalnızca geliştirme). publicKey={}",
                crypto.applicationServerKey());
        return new WebPushKeys(crypto, subject, true, true);
    }

    public record WebPushKeys(WebPushCrypto crypto, String subject, boolean configured, boolean ephemeral) {
        public String publicKey() { return crypto == null ? null : crypto.applicationServerKey(); }
    }
}
