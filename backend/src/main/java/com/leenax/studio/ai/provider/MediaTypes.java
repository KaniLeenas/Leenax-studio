package com.leenax.studio.ai.provider;

import java.util.List;

/** Shared value types for voice, transcription, image and sound adapters. */
public final class MediaTypes {

    private MediaTypes() {
    }

    /** Raw audio or image bytes returned by a provider. cost is in the provider's own unit when it reports one. */
    public record Clip(byte[] bytes, String mimeType, Integer cost) {
    }

    public record VoiceInfo(String id, String name, String category, String gender, String language, String accent,
                            String description, String previewUrl, boolean cloned) {
    }

    /**
     * @param style        free-text delivery direction, e.g. "excited, fast-paced" (used by Gemini TTS)
     * @param stability    0..1, ElevenLabs only
     * @param similarity   0..1, ElevenLabs only
     * @param exaggeration 0..1 style exaggeration, ElevenLabs only
     * @param speed        0.7..1.2 for ElevenLabs; other providers get speed applied by FFmpeg afterwards
     */
    public record SpeechRequest(String model, String voiceId, String text, String languageCode, String style,
                                Double stability, Double similarity, Double exaggeration, Double speed,
                                String previousText, String nextText) {
    }

    public record Sample(byte[] bytes, String filename, String mimeType) {
    }

    public record Word(String text, double start, double end) {
    }

    public record Segment(double start, double end, String text) {
    }

    public record Transcript(String language, String text, List<Word> words, List<Segment> segments) {
    }

    /**
     * @param size        "WIDTHxHEIGHT" for OpenAI
     * @param aspectRatio "16:9" etc. for Gemini
     * @param imageSize   "1K" | "2K" | "4K" for Gemini
     */
    public record ImageRequest(String model, String prompt, String size, String aspectRatio, String imageSize,
                               int count, String quality, boolean transparent, List<Sample> references) {
    }
}
