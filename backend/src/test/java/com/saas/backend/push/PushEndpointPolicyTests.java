package com.saas.backend.push;

import com.saas.backend.auth.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PushEndpointPolicyTests {
    private final PushEndpointPolicy policy = new PushEndpointPolicy("fcm.googleapis.com,push.services.mozilla.com,notify.windows.com,push.apple.com");

    @Test
    void acceptsKnownBrowserPushServices() {
        assertDoesNotThrow(() -> policy.validate("https://fcm.googleapis.com/fcm/send/abc"));
        assertDoesNotThrow(() -> policy.validate("https://updates.push.services.mozilla.com/wpush/v2/abc"));
        assertDoesNotThrow(() -> policy.validate("https://web.push.apple.com/QGuQyavXutnMHT"));
        assertDoesNotThrow(() -> policy.validate("https://db5p.notify.windows.com/w/?token=abc"));
    }

    @Test
    void blocksServerSideRequestForgeryTargets() {
        for (String endpoint : new String[]{
                "http://fcm.googleapis.com/fcm/send/abc", "https://localhost/push", "https://169.254.169.254/latest/meta-data",
                "https://fcm.googleapis.com.evil.test/send", "https://evilfcm.googleapis.com.attacker.test/x",
                "https://user@fcm.googleapis.com/fcm/send/abc", "https://fcm.googleapis.com:8443/fcm/send/abc",
                "https://notgoogleapis.com/fcm", "file:///etc/passwd", "https://" + "a".repeat(1100) + ".fcm.googleapis.com/"}) {
            var error = assertThrows(ApiException.class, () -> policy.validate(endpoint), endpoint);
            assertTrue(error.getCode().equals("PUSH_ENDPOINT_NOT_ALLOWED") || error.getCode().equals("INVALID_PUSH_SUBSCRIPTION"), endpoint);
        }
    }
}
