package com.leenax.studio.scripts;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.projects.Project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompts for ideas, scripts, rewrites and post text, and the parsing of the
 * AI's JSON replies. Kept free of Spring so it can be tested directly.
 */
public final class ScriptWriting {

    private ScriptWriting() {
    }

    /** What kind of video the script is for, with its typical length and shape. */
    public enum VideoType {
        YOUTUBE_LONG("a YouTube video", "8 to 12 minutes"),
        YOUTUBE_SHORT("a YouTube Short", "30 to 60 seconds, vertical"),
        REEL("an Instagram or Facebook Reel", "15 to 60 seconds, vertical"),
        TIKTOK("a TikTok video", "20 to 60 seconds, vertical"),
        TUTORIAL("a tutorial", "5 to 15 minutes, step by step"),
        VLOG("a vlog", "6 to 12 minutes, personal and conversational"),
        DOCUMENTARY("a short documentary", "8 to 20 minutes, factual narration"),
        CINEMATIC("a cinematic film", "1 to 5 minutes, visual storytelling with little dialogue"),
        ENTERTAINMENT("an entertainment video", "3 to 10 minutes, fun and energetic"),
        OTHER("a video", "a length that suits the topic");

        public final String label;
        public final String shape;

        VideoType(String label, String shape) {
            this.label = label;
            this.shape = shape;
        }

        public static VideoType of(String s) {
            try {
                return s == null ? OTHER : valueOf(s);
            } catch (IllegalArgumentException e) {
                return OTHER;
            }
        }
    }

    public record Idea(String title, String hook, String angle) {
    }

    public record Scene(String heading, String narration, String visual) {
    }

    public record Written(String title, String intro, List<Scene> scenes, String outro) {
    }

    static String language(Project.Language l) {
        return l == Project.Language.TA
                ? "Tamil. Write in Tamil script (தமிழ்), in natural spoken Tamil a narrator would say aloud; keep brand names and technical terms in English where Tamil speakers normally do"
                : "English. Plain, natural spoken English a narrator would say aloud";
    }

    public static String ideasSystem() {
        return """
                You help a solo video creator plan videos. Reply with JSON only, no prose:
                {"ideas":[{"title":"...","hook":"one sentence that grabs attention in the first 3 seconds","angle":"what makes this take different"}]}
                Titles must be honest (no clickbait promises the video can't keep) and under 90 characters.""";
    }

    public static String ideasUser(String topic, VideoType type, Project.Language lang, int count, String audience) {
        return "Topic: " + topic + "\nFormat: " + type.label + " (" + type.shape + ")\nLanguage: " + language(lang)
                + (audience == null || audience.isBlank() ? "" : "\nAudience: " + audience)
                + "\nGive " + count + " distinct ideas.";
    }

    public static String scriptSystem() {
        return """
                You write video scripts for a solo creator. Reply with JSON only, no prose:
                {"title":"...","intro":"what the narrator says first","scenes":[{"heading":"short scene name","narration":"exactly what is said","visual":"what the viewer sees"}],"outro":"closing words and call to action"}
                Narration is spoken text only: no stage directions, emojis or hashtags inside it.
                "visual" describes footage, b-roll or on-screen text in one or two sentences.
                Pace the script to the requested length (about 150 spoken words per minute in English, 110 in Tamil).""";
    }

    public static String scriptUser(String title, String idea, VideoType type, Project.Language lang, Integer minutes, String tone,
                                    String notes) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) sb.append("Working title: ").append(title).append('\n');
        sb.append("Idea: ").append(idea).append('\n');
        sb.append("Format: ").append(type.label).append(" (").append(type.shape).append(")\n");
        if (minutes != null) sb.append("Target length: about ").append(minutes).append(minutes == 1 ? " minute\n" : " minutes\n");
        if (tone != null && !tone.isBlank()) sb.append("Tone: ").append(tone).append('\n');
        if (notes != null && !notes.isBlank()) sb.append("Notes from the creator: ").append(notes).append('\n');
        sb.append("Language: ").append(language(lang));
        return sb.toString();
    }

    public static String rewriteSystem() {
        return """
                You improve an existing video script. Keep what works, follow the creator's instruction, and keep the same language unless told otherwise.
                Reply with JSON only, in the same shape:
                {"title":"...","intro":"...","scenes":[{"heading":"...","narration":"...","visual":"..."}],"outro":"..."}""";
    }

    public static String rewriteUser(String instruction, String currentBody, Project.Language lang) {
        return "Instruction: " + instruction + "\nLanguage: " + language(lang) + "\n\nCurrent script:\n" + currentBody;
    }

    public static String metadataSystem() {
        return """
                You write the text that goes with a video on each social platform. Reply with JSON only:
                {"platforms":{"<PLATFORM>":{"title":"...","description":"...","caption":"...","hashtags":["..."]}}}
                Rules: YOUTUBE title ≤ 100 characters and description ≤ 4500 characters with a short summary first;
                INSTAGRAM and TIKTOK captions ≤ 2000 characters with at most 8 hashtags; FACEBOOK a friendly caption;
                LINKEDIN a professional caption without hashtag spam; X ≤ 260 characters.
                Hashtags without the # sign. Keep claims honest and match the script.""";
    }

    public static String metadataUser(String title, String body, List<String> platforms, Project.Language lang) {
        return "Platforms: " + String.join(", ", platforms) + "\nLanguage: " + language(lang) + "\nVideo title: " + title
                + "\n\nScript:\n" + (body.length() > 12_000 ? body.substring(0, 12_000) : body);
    }

    // ---- parsing ----------------------------------------------------------------------

    public static List<Idea> parseIdeas(JsonNode root) {
        List<Idea> out = new ArrayList<>();
        for (JsonNode n : root.path("ideas")) {
            String title = n.path("title").asText("").strip();
            if (title.isEmpty()) continue;
            out.add(new Idea(clip(title, 200), n.path("hook").asText("").strip(), n.path("angle").asText("").strip()));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no ideas in the reply");
        return out;
    }

    public static Written parseScript(JsonNode root) {
        List<Scene> scenes = new ArrayList<>();
        for (JsonNode n : root.path("scenes")) {
            String narration = n.path("narration").asText("").strip();
            String visual = n.path("visual").asText("").strip();
            if (narration.isEmpty() && visual.isEmpty()) continue;
            scenes.add(new Scene(n.path("heading").asText("").strip(), narration, visual));
        }
        String intro = root.path("intro").asText("").strip();
        String outro = root.path("outro").asText("").strip();
        if (scenes.isEmpty() && intro.isEmpty()) throw new IllegalArgumentException("no script in the reply");
        return new Written(clip(root.path("title").asText("").strip(), 200), intro, scenes, outro);
    }

    /** The script as one readable text, the form the creator edits. */
    public static String render(Written w) {
        StringBuilder sb = new StringBuilder();
        if (!w.intro().isEmpty()) sb.append("[INTRO]\n").append(w.intro()).append("\n\n");
        int i = 1;
        for (Scene s : w.scenes()) {
            sb.append("[SCENE ").append(i++).append(s.heading().isEmpty() ? "" : ": " + s.heading()).append("]\n");
            if (!s.visual().isEmpty()) sb.append("(Visual: ").append(s.visual()).append(")\n");
            sb.append(s.narration()).append("\n\n");
        }
        if (!w.outro().isEmpty()) sb.append("[OUTRO]\n").append(w.outro()).append('\n');
        return sb.toString().strip();
    }

    public static Map<String, Object> sections(Written w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intro", w.intro());
        List<Map<String, String>> scenes = new ArrayList<>();
        for (Scene s : w.scenes()) scenes.add(Map.of("heading", s.heading(), "narration", s.narration(), "visual", s.visual()));
        m.put("scenes", scenes);
        m.put("outro", w.outro());
        return m;
    }

    /** Per-platform text, cleaned: hashtags without '#', duplicates and empty ones dropped. */
    public static Map<String, Object> parseMetadata(JsonNode root, List<String> platforms) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode p = root.path("platforms");
        for (String platform : platforms) {
            JsonNode n = p.path(platform);
            if (n.isMissingNode()) continue;
            List<String> tags = new ArrayList<>();
            for (JsonNode t : n.path("hashtags")) {
                String tag = t.asText("").strip().replaceAll("^#+", "").replaceAll("\\s+", "");
                if (!tag.isEmpty() && !tags.contains(tag)) tags.add(tag);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", n.path("title").asText(""));
            entry.put("description", n.path("description").asText(""));
            entry.put("caption", n.path("caption").asText(""));
            entry.put("hashtags", tags);
            out.put(platform, entry);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no platform text in the reply");
        return out;
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
