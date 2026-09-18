package com.saas.backend.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Kuyruktaki kısa ömürlü hesap bağlantılarını AES-256-GCM ile korur. */
@Component
public class AccountMailCipher {
    private static final int NONCE_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AccountMailCipher(@Value("${app.account.mail-key:}") String configuredKey,
                             @Value("${app.jwt.secret}") String developmentFallback) {
        this.key = new SecretKeySpec(keyBytes(configuredKey, developmentFallback), "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Hesap e-postası şifrelenemedi", ex);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] packed = Base64.getDecoder().decode(encoded);
            if (packed.length <= NONCE_BYTES) throw new IllegalArgumentException("Şifreli hesap e-postası geçersiz");
            byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Hesap e-postası çözülemedi", ex);
        }
    }

    public static boolean validConfiguredKey(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return Base64.getDecoder().decode(value).length == 32;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte[] keyBytes(String configured, String fallback) {
        if (configured != null && !configured.isBlank()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(configured);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("APP_ACCOUNT_MAIL_KEY geçerli Base64 olmalı", ex);
            }
            if (decoded.length != 32) throw new IllegalArgumentException("APP_ACCOUNT_MAIL_KEY 32 bayt olmalı");
            return decoded;
        }
        try {
            // Yalnız yerel geliştirme kolaylığı. Üretim açılış denetimi ayrı anahtarı zorunlu kılar.
            return MessageDigest.getInstance("SHA-256").digest(fallback.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 bulunamadı", ex);
        }
    }
}
