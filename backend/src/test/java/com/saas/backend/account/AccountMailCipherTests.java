package com.saas.backend.account;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AccountMailCipherTests {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithRandomNonceAndAuthenticatesCiphertext() {
        var cipher = new AccountMailCipher(KEY, "unused-development-secret");
        String first = cipher.encrypt("reset-token-and-recipient");
        String second = cipher.encrypt("reset-token-and-recipient");

        assertNotEquals(first, second);
        assertFalse(first.contains("reset-token"));
        assertEquals("reset-token-and-recipient", cipher.decrypt(first));

        byte[] tampered = Base64.getDecoder().decode(first);
        tampered[tampered.length - 1] ^= 1;
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(Base64.getEncoder().encodeToString(tampered)));
    }

    @Test
    void productionKeyMustBeExactly32BytesOfBase64() {
        assertTrue(AccountMailCipher.validConfiguredKey(KEY));
        assertFalse(AccountMailCipher.validConfiguredKey(""));
        assertFalse(AccountMailCipher.validConfiguredKey("not-base64"));
        assertFalse(AccountMailCipher.validConfiguredKey(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
