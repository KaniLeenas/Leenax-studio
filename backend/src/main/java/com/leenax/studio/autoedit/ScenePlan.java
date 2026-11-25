package com.leenax.studio.autoedit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Scenes of a script: what is said, what should be seen, and optional on-screen words. */
public final class ScenePlan {

    public record Scene(String narration, String visual, String onScreenText) {
    }

    public static final int MAX_SCENES = 60;

    private ScenePlan() {
    }

    /** Reads {"title": "...", "scenes":[{"narration","visual","onScreenText"}]} (or a bare array). */
    public static Parsed parse(JsonNode node) {
        JsonNode arr = node.isArray() ? node : node.path("scenes");
        if (!arr.isArray() || arr.isEmpty()) throw new IllegalArgumentException("The AI didn't return any scenes");
        List<Scene> scenes = new ArrayList<>();
        for (JsonNode s : arr) {
            String narration = s.path("narration").asText("").strip();
            if (narration.isEmpty()) continue;
            String visual = s.path("visual").asText("").strip();
            String text = s.path("onScreenText").asText("").strip();
            scenes.add(new Scene(narration, visual, text.length() > 80 ? text.substring(0, 80) : text));
            if (scenes.size() == MAX_SCENES) break;
        }
        if (scenes.isEmpty()) throw new IllegalArgumentException("The AI's scenes had no narration");
        String title = node.isObject() ? node.path("title").asText("").strip() : "";
        return new Parsed(title.length() > 100 ? title.substring(0, 100) : title, scenes);
    }

    public record Parsed(String title, List<Scene> scenes) {
    }

    /** Without an AI account: one scene per paragraph (or every ~3 sentences). */
    public static List<Scene> splitPlainly(String script) {
        List<Scene> out = new ArrayList<>();
        for (String para : script.strip().split("\\n\\s*\\n")) {
            String p = para.replaceAll("\\s+", " ").strip();
            if (p.isEmpty()) continue;
            String[] sentences = p.split("(?<=[.!?।])\\s+");
            StringBuilder cur = new StringBuilder();
            int n = 0;
            for (String s : sentences) {
                if (cur.length() > 0) cur.append(' ');
                cur.append(s);
                if (++n == 3) {
                    out.add(new Scene(cur.toString(), "", ""));
                    cur.setLength(0);
                    n = 0;
                }
            }
            if (cur.length() > 0) out.add(new Scene(cur.toString(), "", ""));
        }
        return out.size() > MAX_SCENES ? out.subList(0, MAX_SCENES) : out;
    }
}
