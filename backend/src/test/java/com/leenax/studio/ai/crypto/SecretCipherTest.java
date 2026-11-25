package com.leenax.studio.ai.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsAndUsesFreshIvEachTime() {
        SecretCipher cipher = SecretCipher.fromBase64(KEY);
        byte[] a = cipher.encrypt("sk-live-தமிழ்-123", "ai_account:1");
        byte[] b = cipher.encrypt("sk-live-தமிழ்-123", "ai_account:1");
        assertFalse(java.util.Arrays.equals(a, b));
        assertEquals("sk-live-தமிழ்-123", cipher.decrypt(a, "ai_account:1"));
        assertFalse(new String(a).contains("sk-live"));
    }

    @Test
    void ciphertextIsBoundToItsAccount() {
        SecretCipher cipher = SecretCipher.fromBase64(KEY);
        byte[] blob = cipher.encrypt("secret", "ai_account:1");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> cipher.decrypt(blob, "ai_account:2"));
        assertTrue(e.getMessage().contains("LEENAX_MASTER_KEY"));
    }

    @Test
    void detectsTamperingAndWrongKey() {
        SecretCipher cipher = SecretCipher.fromBase64(KEY);
        byte[] blob = cipher.encrypt("secret", "x");
        blob[blob.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(blob, "x"));

        byte[] other = new byte[32];
        other[0] = 7;
        byte[] good = cipher.encrypt("secret", "x");
        assertThrows(IllegalStateException.class, () -> new SecretCipher(other).decrypt(good, "x"));
    }

    @Test
    void rejectsBadMasterKeys() {
        assertThrows(IllegalArgumentException.class, () -> SecretCipher.fromBase64(""));
        assertThrows(IllegalArgumentException.class, () -> SecretCipher.fromBase64("not base64!!"));
        assertThrows(IllegalArgumentException.class, () -> SecretCipher.fromBase64(Base64.getEncoder().encodeToString(new byte[16])));
    }

    @Test
    void hintShowsLastFourOnlyForLongKeys() {
        assertEquals("1234", SecretCipher.hint("sk-abcdefgh1234"));
        assertEquals("", SecretCipher.hint("short"));
    }
}
