package com.saas.backend.push;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WebPushCryptoTests {

    /** RFC 8291 Ek A test vektörü: sabit anahtar ve salt ile bayt bayt aynı gövde üretilmeli. */
    @Test
    void reproducesRfc8291AppendixAVector() {
        var sender = WebPushCrypto.keyPair(
                "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
                "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw");
        var receiver = WebPushCrypto.publicKey(WebPushCrypto.decode(
                "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"));
        byte[] body = WebPushCrypto.encrypt(receiver, WebPushCrypto.decode("BTBZMqHH6r4Tts7J_aSIgg"),
                "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8),
                WebPushCrypto.decode("DGv6ra1nlYgDCS1FRnbzlw"), sender);
        assertEquals("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
                WebPushCrypto.encode(body));
    }

    @Test
    void receiverDecryptsRandomisedMessages() throws Exception {
        var client = new WebPushTestClient("https://fcm.googleapis.com/fcm/send/test");
        var crypto = new WebPushCrypto(WebPushCrypto.generateKeyPair());
        String message = "{\"title\":\"Randevun onaylandı\",\"url\":\"/customer/account\"}";
        byte[] first = crypto.encrypt(client.p256dh(), client.auth(), message.getBytes(StandardCharsets.UTF_8));
        byte[] second = crypto.encrypt(client.p256dh(), client.auth(), message.getBytes(StandardCharsets.UTF_8));
        assertNotEquals(WebPushCrypto.encode(first), WebPushCrypto.encode(second), "salt ve gönderici anahtarı her mesajda yenilenmeli");
        assertEquals(message, client.decrypt(first));
        assertEquals(message, client.decrypt(second));
        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt(client.p256dh(), client.auth(), new byte[WebPushCrypto.MAX_PLAINTEXT_BYTES + 1]));
    }

    @Test
    void vapidTokenIsVerifiableEs256WithOriginAudience() throws Exception {
        var keys = WebPushCrypto.generateKeyPair();
        String header = new WebPushCrypto(keys).vapidAuthorization("https://updates.push.services.mozilla.com/wpush/v2/abc",
                "mailto:ops@example.test", Instant.parse("2026-09-17T12:00:00Z"));
        assertTrue(header.startsWith("vapid t="));
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(WebPushCrypto.decode(parts[2])));
        String claims = new String(WebPushCrypto.decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"aud\":\"https://updates.push.services.mozilla.com\""), claims);
        assertTrue(claims.contains("\"exp\":1789689600"), claims);
    }

    @Test
    void rejectsKeysThatAreNotOnTheCurve() {
        var client = new WebPushTestClient("https://fcm.googleapis.com/fcm/send/test");
        byte[] tampered = WebPushCrypto.decode(client.p256dh());
        tampered[64] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.publicKey(tampered));
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.publicKey(new byte[65]));
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.keyPair(client.p256dh(), "c2hvcnQ"));
    }
}
