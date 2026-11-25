package com.leenax.studio.subtitles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Subtitle translation with a text model. Cues go out as a numbered JSON list
 * and must come back with the same numbers, so timing never drifts: only the
 * text is replaced.
 */
public final class CueTranslation {

    public static final int BATCH = 60;
    private static final ObjectMapper JSON = new ObjectMapper();

    private CueTranslation() {
    }

    public static String systemPrompt(String targetLanguageName) {
        return """
                You translate video subtitles into %s.
                You receive a JSON array of objects {"i": number, "t": text}. Return ONLY a JSON array with the same objects in the same order, \
                each with the same "i" and "t" translated. Translate every item, never merge or split items.
                Keep subtitles short and natural for reading on screen. Keep "\\n" line breaks where the meaning allows. \
                Keep names of people, places, brands and channels as they are. For Tamil, use natural spoken Tamil in Tamil script.
                No commentary, no code fences.""".formatted(targetLanguageName);
    }

    public static String userPrompt(List<Cue> cues, int offset) {
        ArrayNode arr = JSON.createArrayNode();
        for (int i = 0; i < cues.size(); i++) {
            arr.addObject().put("i", offset + i).put("t", cues.get(i).text());
        }
        return arr.toString();
    }

    /** Reads the model's reply; tolerates code fences or chatter around the array. */
    public static Map<Integer, String> parse(String reply) {
        if (reply == null) throw new IllegalArgumentException("Empty reply");
        int start = reply.indexOf('[');
        int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) throw new IllegalArgumentException("The translation was not in the expected format");
        try {
            JsonNode arr = JSON.readTree(reply.substring(start, end + 1));
            Map<Integer, String> out = new HashMap<>();
            for (JsonNode n : arr) {
                if (n.has("i") && n.has("t") && n.get("t").isTextual()) out.put(n.get("i").asInt(), n.get("t").asText().strip());
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("The translation was not in the expected format");
        }
    }

    /** Same timing, translated text. Throws when an item is missing. */
    public static List<Cue> apply(List<Cue> cues, Map<Integer, String> translated) {
        List<Cue> out = new ArrayList<>(cues.size());
        for (int i = 0; i < cues.size(); i++) {
            String t = translated.get(i);
            if (t == null || t.isBlank()) throw new IllegalArgumentException("The translation skipped subtitle " + (i + 1));
            out.add(new Cue(cues.get(i).start(), cues.get(i).end(), t.replace("\\n", "\n")));
        }
        return out;
    }

    public static String languageName(String code) {
        return switch (code.toLowerCase()) {
            case "ta" -> "Tamil";
            case "en" -> "English";
            case "si" -> "Sinhala";
            case "hi" -> "Hindi";
            case "ml" -> "Malayalam";
            case "te" -> "Telugu";
            case "kn" -> "Kannada";
            case "fr" -> "French";
            case "de" -> "German";
            case "es" -> "Spanish";
            case "ar" -> "Arabic";
            case "zh" -> "Chinese (Simplified)";
            case "ja" -> "Japanese";
            case "ms" -> "Malay";
            default -> code;
        };
    }
}
