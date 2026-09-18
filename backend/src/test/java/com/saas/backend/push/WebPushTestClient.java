package com.saas.backend.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * Testlerde tarayıcıyı (user agent) temsil eder: abonelik anahtarları üretir ve sunucunun gönderdiği
 * aes128gcm gövdesini RFC 8291'in alıcı tarafı adımlarıyla çözer.
 */
public final class WebPushTestClient {
    private final KeyPair keys = WebPushCrypto.generateKeyPair();
    private final byte[] authSecret = new byte[16];
    private final String endpoint;

    public WebPushTestClient(String endpoint) {
        this.endpoint = endpoint;
        new SecureRandom().nextBytes(authSecret);
    }

    public String endpoint() { return endpoint; }
    public String p256dh() { return WebPushCrypto.encode(WebPushCrypto.rawPublicKey((ECPublicKey) keys.getPublic())); }
    public String auth() { return WebPushCrypto.encode(authSecret); }

    public String subscriptionJson() {
        return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"" + p256dh() + "\",\"auth\":\"" + auth() + "\"}}";
    }

    public String decrypt(byte[] body) throws Exception {
        var buffer = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        buffer.get(salt);
        int recordSize = buffer.getInt();
        int keyIdLength = Byte.toUnsignedInt(buffer.get());
        if (recordSize < 18 || keyIdLength != 65) throw new IllegalStateException("Beklenmeyen aes128gcm başlığı");
        byte[] senderPublic = new byte[keyIdLength];
        buffer.get(senderPublic);
        byte[] ciphertext = Arrays.copyOfRange(body, buffer.position(), body.length);

        var agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(keys.getPrivate());
        agreement.doPhase(WebPushCrypto.publicKey(senderPublic), true);
        byte[] ecdhSecret = agreement.generateSecret();
        byte[] uaPublic = WebPushCrypto.rawPublicKey((ECPublicKey) keys.getPublic());

        byte[] prkKey = WebPushCrypto.hmac(authSecret, ecdhSecret);
        byte[] ikm = WebPushCrypto.hkdfExpand(prkKey, WebPushCrypto.concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublic, senderPublic), 32);
        byte[] prk = WebPushCrypto.hmac(salt, ikm);
        byte[] cek = WebPushCrypto.hkdfExpand(prk, WebPushCrypto.concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0}), 16);
        byte[] nonce = WebPushCrypto.hkdfExpand(prk, WebPushCrypto.concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0}), 12);

        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = cipher.doFinal(ciphertext);
        int end = plain.length - 1;
        while (end >= 0 && plain[end] == 0) end--; // dolgu
        if (end < 0 || plain[end] != 2) throw new IllegalStateException("Son kayıt ayracı bulunamadı");
        return new String(plain, 0, end, StandardCharsets.UTF_8);
    }
}
