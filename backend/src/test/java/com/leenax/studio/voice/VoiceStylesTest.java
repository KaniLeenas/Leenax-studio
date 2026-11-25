package com.leenax.studio.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceStylesTest {

    @Test
    void expressiveStylesAreLessStableThanDocumentary() {
        assertTrue(VoiceStyles.eleven(VoiceStyles.Emotion.EXCITED).stability() < VoiceStyles.eleven(VoiceStyles.Emotion.DOCUMENTARY).stability());
        assertEquals(0.0, VoiceStyles.eleven(null).exaggeration(), 1e-9);
    }

    @Test
    void directionsCombineWithExtraNotes() {
        assertEquals("Say this with high energy and excitement. Tamil Nadu accent",
                VoiceStyles.direction(VoiceStyles.Emotion.EXCITED, " Tamil Nadu accent "));
        assertEquals(null, VoiceStyles.direction(VoiceStyles.Emotion.NEUTRAL, ""));
        assertEquals(VoiceStyles.Emotion.SAD, VoiceStyles.parse("sad"));
        assertEquals(VoiceStyles.Emotion.NEUTRAL, VoiceStyles.parse("whatever"));
    }

    @Test
    void speedSplitsBetweenProviderAndFfmpeg() {
        double[] s = VoiceStyles.splitSpeed(1.5, true);
        assertEquals(1.2, s[0], 1e-9);
        assertEquals(1.25, s[1], 1e-9);
        double[] g = VoiceStyles.splitSpeed(0.9, false);
        assertEquals(1.0, g[0], 1e-9);
        assertEquals(0.9, g[1], 1e-9);
        assertEquals(1.0, VoiceStyles.splitSpeed(1.0, true)[1], 1e-9);
    }

    @Test
    void consentNeedsTheWholeSentence() {
        assertTrue(Consent.matches("  i confirm this is my own voice and I consent to creating a private AI   clone of it. "));
        assertFalse(Consent.matches("I confirm"));
        assertFalse(Consent.matches(null));
    }
}
