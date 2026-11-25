package com.leenax.studio.voice;

import java.util.Locale;

/**
 * Delivery styles. ElevenLabs is steered with voice settings (stability lower =
 * more expressive; style = exaggeration). Gemini TTS is steered with a plain
 * English direction attached to the text.
 */
public final class VoiceStyles {

    public enum Emotion { NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, STORYTELLING, DOCUMENTARY, PROFESSIONAL }

    public record ElevenSettings(double stability, double similarity, double exaggeration) {
    }

    private VoiceStyles() {
    }

    public static ElevenSettings eleven(Emotion e) {
        return switch (e == null ? Emotion.NEUTRAL : e) {
            case NEUTRAL -> new ElevenSettings(0.5, 0.75, 0.0);
            case HAPPY -> new ElevenSettings(0.38, 0.75, 0.45);
            case SAD -> new ElevenSettings(0.55, 0.8, 0.35);
            case ANGRY -> new ElevenSettings(0.3, 0.75, 0.6);
            case EXCITED -> new ElevenSettings(0.3, 0.75, 0.55);
            case STORYTELLING -> new ElevenSettings(0.45, 0.75, 0.35);
            case DOCUMENTARY -> new ElevenSettings(0.7, 0.8, 0.1);
            case PROFESSIONAL -> new ElevenSettings(0.65, 0.8, 0.05);
        };
    }

    /** Direction for prompt-steered voices (Gemini). Null for neutral. */
    public static String direction(Emotion e, String extra) {
        String base = switch (e == null ? Emotion.NEUTRAL : e) {
            case NEUTRAL -> null;
            case HAPPY -> "Say this in a warm, happy, smiling tone";
            case SAD -> "Say this softly, in a sad and heartfelt tone";
            case ANGRY -> "Say this in an angry, forceful tone";
            case EXCITED -> "Say this with high energy and excitement";
            case STORYTELLING -> "Narrate this like an engaging storyteller, with natural pauses and expression";
            case DOCUMENTARY -> "Narrate this like a calm, authoritative documentary voice-over";
            case PROFESSIONAL -> "Say this in a clear, confident, professional tone";
        };
        String more = extra == null || extra.isBlank() ? null : extra.strip();
        if (base == null) return more;
        return more == null ? base : base + ". " + more;
    }

    public static Emotion parse(String value) {
        if (value == null || value.isBlank()) return Emotion.NEUTRAL;
        try {
            return Emotion.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Emotion.NEUTRAL;
        }
    }

    /**
     * Splits the requested speed between the provider and FFmpeg: ElevenLabs
     * handles 0.7–1.2 natively (better quality), the remainder is applied after.
     */
    public static double[] splitSpeed(double requested, boolean nativeSupport) {
        double s = Math.max(0.5, Math.min(2.0, requested));
        if (!nativeSupport) return new double[]{1.0, s};
        double nativePart = Math.max(0.7, Math.min(1.2, s));
        return new double[]{nativePart, s / nativePart};
    }
}
