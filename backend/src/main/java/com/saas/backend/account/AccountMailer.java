package com.saas.backend.account;

import com.saas.backend.operations.GmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Hesap güvenliği e-postaları. Posta gönderimi yapılandırılmamışsa (yerel geliştirme)
 * bağlantı yalnızca sunucu günlüğüne yazılır; istek yine de başarıyla sonuçlanır, böylece
 * "bu e-posta kayıtlı mı" bilgisi yanıttan anlaşılamaz.
 */
@Component
public class AccountMailer {
    private static final Logger LOG = LoggerFactory.getLogger(AccountMailer.class);

    private final GmailSender mail;
    private final String baseUrl;

    public AccountMailer(GmailSender mail, @Value("${app.public-base-url:http://localhost:5173}") String baseUrl) {
        this.mail = mail;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public void passwordReset(String recipient, String token, boolean customer) {
        String link = link(customer ? "/musteri/sifre-sifirla" : "/sifre-sifirla", token);
        deliver(recipient, "Şifre sıfırlama bağlantısı", """
                Merhaba,

                Şifrenizi sıfırlamak için aşağıdaki bağlantıyı kullanabilirsiniz. Bağlantı bir saat geçerlidir
                ve yalnızca bir kez kullanılabilir.

                %s

                Bu isteği siz yapmadıysanız hiçbir şey yapmanıza gerek yok; mevcut şifreniz geçerli kalır.
                """.formatted(link));
    }

    public void emailVerification(String recipient, String token, boolean customer) {
        String link = link(customer ? "/musteri/eposta-dogrula" : "/eposta-dogrula", token);
        deliver(recipient, "E-posta adresinizi doğrulayın", """
                Merhaba,

                E-posta adresinizi doğrulamak için aşağıdaki bağlantıya gidin:

                %s

                Bağlantı 48 saat geçerlidir.
                """.formatted(link));
    }

    private String link(String path, String token) {
        return baseUrl + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private void deliver(String recipient, String subject, String body) {
        if (!mail.ready() || !mail.allowed(recipient)) {
            // Yalnızca geliştirme: posta yapılandırılmadığında akışın tamamlanabilmesi için
            // bağlantı sunucu günlüğüne yazılır. Üretimde posta zorunludur (MAIL_ENABLED=true).
            LOG.info("Hesap e-postası gönderilemedi (posta yapılandırılmamış) subject={} body=\n{}", subject, body);
            return;
        }
        try {
            mail.send(UUID.randomUUID(), recipient, subject, body);
        } catch (RuntimeException ex) {
            // Gönderim hatası kullanıcıya yansıtılmaz: aksi halde hangi adreslerin kayıtlı olduğu anlaşılırdı.
            LOG.error("Hesap e-postası gönderilemedi subject={} error={}", subject, ex.getClass().getSimpleName());
        }
    }
}
