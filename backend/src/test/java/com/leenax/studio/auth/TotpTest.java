package com.leenax.studio.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TOTP against the RFC 6238 test vectors (SHA-1 secret "12345678901234567890"), last six digits. */
class TotpTest {

    private static final String SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfcVectors() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", SECRET);
        assertEquals("287082", Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(59))));
        assertEquals("081804", Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(1111111109))));
        assertEquals("005924", Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(1234567890))));
        assertEquals("279037", Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(2000000000))));
    }

    @Test
    void acceptsOneStepOfClockDriftAndRefusesReplays() {
        Instant now = Instant.ofEpochSecond(1234567890);
        long step = Totp.step(now);
        assertEquals(step, Totp.verify(SECRET, "005924", now, null));
        assertEquals(step - 1, Totp.verify(SECRET, Totp.code(SECRET, step - 1), now, null));
        assertEquals(step + 1, Totp.verify(SECRET, Totp.code(SECRET, step + 1), now, null));
        assertEquals(-1, Totp.verify(SECRET, Totp.code(SECRET, step + 2), now, null));
        assertEquals(-1, Totp.verify(SECRET, "005924", now, step));          // already used
        assertEquals(step, Totp.verify(SECRET, "005 924", now, step - 1));   // spaces are fine
        assertEquals(-1, Totp.verify(SECRET, "abc123", now, null));
        assertEquals(-1, Totp.verify(SECRET, null, now, null));
    }

    @Test
    void secretsAreRandomBase32AndRoundTrip() {
        String s = Totp.newSecret();
        assertEquals(32, s.length());
        assertTrue(s.matches("[A-Z2-7]+"));
        assertFalse(s.equals(Totp.newSecret()));
        assertEquals(s, Totp.base32(Totp.unbase32(s)));
    }

    @Test
    void otpauthLinkHasTheStandardFields() {
        String uri = Totp.uri("Leenax Studio", "kani@example.com", "ABC");
        assertEquals("otpauth://totp/Leenax%20Studio:kani%40example.com?secret=ABC&issuer=Leenax%20Studio&algorithm=SHA1&digits=6&period=30", uri);
    }

    @Test
    void recoveryCodesAreReadableAndCompareLoosely() {
        String c = MfaService.recoveryCode();
        assertTrue(c.matches("[a-z2-9]{5}-[a-z2-9]{5}"), c);
        assertFalse(c.matches(".*[01oli].*"));
        assertEquals(MfaService.hash(MfaService.normalise(c)), MfaService.hash(MfaService.normalise(" " + c.toUpperCase().replace("-", " ") + " ")));
        assertEquals(64, MfaService.hash("x").length());
    }
}
