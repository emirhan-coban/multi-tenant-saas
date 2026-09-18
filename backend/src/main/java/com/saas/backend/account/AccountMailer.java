package com.saas.backend.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Hesap güvenliği e-postaları. Bağlantı ve token hiçbir koşulda günlüğe yazılmaz. */
@Component
public class AccountMailer {
    private static final Logger LOG = LoggerFactory.getLogger(AccountMailer.class);

    private final AccountMailQueue queue;
    private final String baseUrl;

    public AccountMailer(AccountMailQueue queue, @Value("${app.public-base-url:http://localhost:5173}") String baseUrl) {
        this.queue = queue;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public boolean passwordReset(String recipient, String token, boolean customer) {
        String link = link(customer ? "/musteri/sifre-sifirla" : "/sifre-sifirla", token);
        return deliver(recipient, "Şifre sıfırlama bağlantısı", """
                Merhaba,

                Şifrenizi sıfırlamak için aşağıdaki bağlantıyı kullanabilirsiniz. Bağlantı bir saat geçerlidir
                ve yalnızca bir kez kullanılabilir.

                %s

                Bu isteği siz yapmadıysanız hiçbir şey yapmanıza gerek yok; mevcut şifreniz geçerli kalır.
                """.formatted(link), token);
    }

    public boolean emailVerification(String recipient, String token, boolean customer) {
        String link = link(customer ? "/musteri/eposta-dogrula" : "/eposta-dogrula", token);
        return deliver(recipient, "E-posta adresinizi doğrulayın", """
                Merhaba,

                E-posta adresinizi doğrulamak için aşağıdaki bağlantıya gidin:

                %s

                Bağlantı 48 saat geçerlidir.
                """.formatted(link), token);
    }

    private String link(String path, String token) {
        return baseUrl + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private boolean deliver(String recipient, String subject, String body, String token) {
        try {
            queue.enqueue(recipient, subject, body, token);
            return true;
        } catch (RuntimeException ex) {
            // Yanıt ve log hesap varlığını, alıcıyı veya gizli bağlantıyı sızdırmamalı.
            LOG.error("Hesap e-postası kuyruğa alınamadı subject={} error={}", subject, ex.getClass().getSimpleName());
            return false;
        }
    }
}
