package com.leenax.studio.autoedit;

import java.util.Locale;

/** System prompts for the auto-editor. All ask for JSON only; replies are parsed with {@link LlmJson}. */
public final class Prompts {

    private Prompts() {
    }

    static String languageName(String code) {
        return "TA".equalsIgnoreCase(code) ? "Tamil (natural spoken Tamil, in Tamil script)" : "English";
    }

    /** Words per minute of comfortable narration, used to size scripts. */
    static int wordsPerMinute(String language) {
        return "TA".equalsIgnoreCase(language) ? 100 : 145;
    }

    public static String writeScript(String language, int seconds, String style) {
        int words = Math.max(20, wordsPerMinute(language) * seconds / 60);
        return """
                You write narration scripts for a solo YouTube and social media creator.
                Write in %s. Style: %s.
                The video is about %d seconds long, so write about %d words of narration in total.
                Open with a hook in the first sentence. Short, spoken sentences. No stage directions inside the narration.
                Split it into scenes of 1–3 sentences. For each scene describe what the viewer should see (in English, concrete and visual),
                and optionally 1–5 words of on-screen text.
                Reply with JSON only, no code fences:
                {"title": "...", "scenes": [{"narration": "...", "visual": "...", "onScreenText": ""}]}"""
                .formatted(languageName(language), style == null || style.isBlank() ? "friendly and clear" : style, seconds, words);
    }

    public static String splitScript() {
        return """
                You prepare a creator's narration script for editing.
                Split the script into scenes of 1–3 sentences. Keep the narration text EXACTLY as written (same words, same language, same order);
                do not translate, shorten or add anything. Keep [pause …] markers where they are.
                For each scene describe in English what the viewer should see, and optionally 1–5 words of on-screen text
                in the script's language.
                Reply with JSON only, no code fences:
                {"title": "...", "scenes": [{"narration": "...", "visual": "...", "onScreenText": ""}]}""";
    }

    public static String matchShots() {
        return """
                You are a video editor choosing B-roll for narrated scenes.
                You get numbered scenes (with what is said, what should be seen, and how many seconds the scene lasts)
                and a catalogue of the creator's footage (F1, F2…) with descriptions and lengths.
                For each scene pick shots that fit what is being said. Change shot every 2–6 seconds.
                Use times inside each video's length; don't use the same moment twice; images can be any length.
                The shots of a scene should add up to about the scene's seconds. Use only the given references.
                Reply with JSON only, no code fences:
                {"scenes": [{"scene": 1, "shots": [{"f": "F2", "start": 3.5, "end": 8.0}]}]}""";
    }

    public static String pickHighlights(int count, int minSeconds, int maxSeconds, String language) {
        return String.format(Locale.ROOT, """
                You find the best moments in a long video for YouTube Shorts, Instagram Reels and TikTok.
                You get the transcript with start times in seconds ([12.3] text).
                Pick up to %d moments, each %d–%d seconds long, that make sense on their own, start with a strong hook,
                and end at a natural point. Don't overlap them. Prefer surprising facts, emotion, humour and clear tips.
                For each, write a short catchy title (max 6 words) in %s and say in one English sentence why it works.
                Reply with JSON only, no code fences:
                {"highlights": [{"start": 12.3, "end": 55.0, "title": "...", "hook": "..."}]}""",
                count, minSeconds, maxSeconds, languageName(language));
    }

    public static String describeFootage() {
        return """
                You catalogue a video creator's footage so an editor can find shots.
                The pictures are frames from one clip (start, middle, end). In one English sentence of at most 30 words,
                say what is shown: subject, place, action, framing (close-up, wide, aerial…), mood and light.
                Then give up to 8 comma-separated keywords. Reply as plain text:
                <sentence>
                Keywords: <keywords>""";
    }
}
