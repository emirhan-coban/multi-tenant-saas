package com.saas.backend.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Harici kütüphane gerektirmeyen Web Push kriptografisi.
 * <ul>
 *   <li>RFC 8291 — Message Encryption for Web Push (aes128gcm içerik kodlaması, RFC 8188)</li>
 *   <li>RFC 8292 — VAPID (ES256 imzalı JWT)</li>
 * </ul>
 * Örnek VAPID anahtar çiftiyle oluşturulur, değişmez alanlara sahiptir ve thread-safe'tir.
 */
public final class WebPushCrypto {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final int RECORD_SIZE = 4096;
    /**
     * RFC 8291 §4 tek kayıt ve push servislerinin 4096 baytlık gövde sınırı: 4096 - 86 (başlık) - 16 (GCM etiketi) - 1 (ayraç) = 3993.
     * Bildirimlerimiz birkaç yüz bayttır; bilinçli olarak daha düşük bir üst sınır uygulanır.
     */
    public static final int MAX_PLAINTEXT_BYTES = 3000;
    private static final ECParameterSpec P256 = p256();

    private final ECPublicKey vapidPublic;
    private final ECPrivateKey vapidPrivate;
    private final SecureRandom random;

    public WebPushCrypto(KeyPair vapidKeys) {
        this(vapidKeys, new SecureRandom());
    }

    WebPushCrypto(KeyPair vapidKeys, SecureRandom random) {
        this.vapidPublic = (ECPublicKey) vapidKeys.getPublic();
        this.vapidPrivate = (ECPrivateKey) vapidKeys.getPrivate();
        this.random = random;
    }

    /** Kalıcı VAPID anahtarı üretir: {@code java backend/src/main/java/com/saas/backend/push/WebPushCrypto.java} */
    public static void main(String[] args) {
        var keys = generateKeyPair();
        System.out.println("APP_PUSH_VAPID_PUBLIC_KEY=" + encode(rawPublicKey((ECPublicKey) keys.getPublic())));
        System.out.println("APP_PUSH_VAPID_PRIVATE_KEY=" + encode(rawPrivateKey((ECPrivateKey) keys.getPrivate())));
    }

    // ----------------------------------------------------------------- anahtarlar

    public static KeyPair generateKeyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("P-256 anahtarı üretilemedi", ex);
        }
    }

    /** base64url ham anahtarlar (public: 65 bayt sıkıştırılmamış nokta, private: 32 bayt d). */
    public static KeyPair keyPair(String publicKeyB64, String privateKeyB64) {
        byte[] d = decode(privateKeyB64);
        if (d.length != 32) throw new IllegalArgumentException("VAPID private key 32 bayt olmalı");
        try {
            var factory = KeyFactory.getInstance("EC");
            var privateKey = factory.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
            var publicKey = publicKey(decode(publicKeyB64));
            return new KeyPair(publicKey, privateKey);
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("VAPID anahtarları geçersiz", ex);
        }
    }

    public static ECPublicKey publicKey(byte[] uncompressed) {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04)
            throw new IllegalArgumentException("P-256 public key 65 bayt sıkıştırılmamış nokta olmalı");
        var x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        var y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        // KeyFactory noktanın eğri üzerinde olduğunu doğrulamaz; geçersiz eğri saldırısına karşı açıkça kontrol edilir.
        if (!onCurve(x, y)) throw new IllegalArgumentException("P-256 public key eğri üzerinde değil");
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("P-256 public key geçersiz", ex);
        }
    }

    public static byte[] rawPublicKey(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        copyUnsigned(key.getW().getAffineX(), out, 1);
        copyUnsigned(key.getW().getAffineY(), out, 33);
        return out;
    }

    public static byte[] rawPrivateKey(ECPrivateKey key) {
        byte[] out = new byte[32];
        copyUnsigned(key.getS(), out, 0);
        return out;
    }

    public String applicationServerKey() {
        return encode(rawPublicKey(vapidPublic));
    }

    // ----------------------------------------------------------------- RFC 8291

    /** Tarayıcı aboneliği için yükü şifreler ve istek gövdesini döndürür. */
    public byte[] encrypt(String uaPublicB64, String authSecretB64, byte[] plaintext) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return encrypt(publicKey(decode(uaPublicB64)), decode(authSecretB64), plaintext, salt, generateKeyPair());
    }

    static byte[] encrypt(ECPublicKey uaPublic, byte[] authSecret, byte[] plaintext, byte[] salt, KeyPair senderKeys) {
        if (authSecret.length != 16) throw new IllegalArgumentException("auth secret 16 bayt olmalı");
        if (salt.length != 16) throw new IllegalArgumentException("salt 16 bayt olmalı");
        if (plaintext.length > MAX_PLAINTEXT_BYTES) throw new IllegalArgumentException("Bildirim yükü çok büyük");
        try {
            byte[] uaRaw = rawPublicKey(uaPublic);
            byte[] asRaw = rawPublicKey((ECPublicKey) senderKeys.getPublic());

            var agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(senderKeys.getPrivate());
            agreement.doPhase(uaPublic, true);
            byte[] ecdhSecret = agreement.generateSecret();

            byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaRaw, asRaw);
            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] ikm = hkdfExpand(prkKey, keyInfo, 32);

            byte[] prk = hmac(salt, ikm);
            byte[] cek = hkdfExpand(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0}), 16);
            byte[] nonce = hkdfExpand(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0}), 12);

            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{2}));

            var header = ByteBuffer.allocate(16 + 4 + 1 + asRaw.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asRaw.length).put(asRaw);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Web push şifrelemesi başarısız", ex);
        }
    }

    // ----------------------------------------------------------------- RFC 8292

    /** {@code Authorization} başlığının değeri: {@code vapid t=<jwt>, k=<public key>}. */
    public String vapidAuthorization(String endpoint, String subject, Instant now) {
        var uri = java.net.URI.create(endpoint);
        String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        long expires = now.plus(Duration.ofHours(12)).getEpochSecond();
        String header = encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = encode(("{\"aud\":\"" + jsonEscape(audience) + "\",\"exp\":" + expires
                + ",\"sub\":\"" + jsonEscape(subject) + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        try {
            // P1363 formatı JWS'nin beklediği 64 baytlık R||S imzasını doğrudan üretir.
            var signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(vapidPrivate);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return "vapid t=" + signingInput + "." + encode(signer.sign()) + ", k=" + applicationServerKey();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("VAPID imzası üretilemedi", ex);
        }
    }

    // ----------------------------------------------------------------- yardımcılar

    static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        if (length > 32) throw new IllegalArgumentException("Tek blok HKDF en fazla 32 bayt üretir");
        return Arrays.copyOf(hmac(prk, concat(info, new byte[]{1})), length);
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    public static byte[] decode(String value) {
        if (value == null) throw new IllegalArgumentException("Boş base64url değeri");
        String normalized = value.trim().replace('+', '-').replace('/', '_');
        while (normalized.endsWith("=")) normalized = normalized.substring(0, normalized.length() - 1);
        return URL_DECODER.decode(normalized);
    }

    public static String encode(byte[] value) {
        return URL_ENCODER.encodeToString(value);
    }

    private static boolean onCurve(BigInteger x, BigInteger y) {
        var field = (ECFieldFp) P256.getCurve().getField();
        BigInteger p = field.getP();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) return false;
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.pow(3).add(P256.getCurve().getA().multiply(x)).add(P256.getCurve().getB()).mod(p);
        return left.equals(right);
    }

    private static void copyUnsigned(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, target, offset + 32 - length, length);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ECParameterSpec p256() {
        try {
            var parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("P-256 parametreleri yüklenemedi", ex);
        }
    }
}
