package com.leenax.studio.storyboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.autoedit.TimelineBuilder;
import com.leenax.studio.characters.StoryCharacter;
import com.leenax.studio.projects.Project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turning a script into storyboard scenes, and a scene into the prompts sent to
 * image and video models. Pure functions, tested directly.
 */
public final class StoryboardPlanning {

    private StoryboardPlanning() {
    }

    public static final Set<String> ANGLES = Set.of("WIDE", "MEDIUM", "CLOSE_UP", "EXTREME_CLOSE_UP", "OVER_THE_SHOULDER",
            "LOW_ANGLE", "HIGH_ANGLE", "AERIAL", "POV", "TOP_DOWN");
    public static final Set<String> MOVES = Set.of("STATIC", "PAN", "TILT", "DOLLY_IN", "DOLLY_OUT", "TRACKING", "HANDHELD",
            "CRANE", "ORBIT", "ZOOM_IN", "ZOOM_OUT");
    public static final int MAX_SCENES = 60;

    /** A scene as the AI proposed it, before it is saved. */
    public record Draft(String title, String description, String narration, List<Map<String, Object>> dialogue,
                        double durationSeconds, String cameraAngle, String cameraMovement, String lighting,
                        String composition, String sound, List<String> characterNames) {
    }

    public record Plan(String style, List<Draft> scenes) {
    }

    public static String scenesSystem() {
        return """
                You are a storyboard artist for a solo video creator. Split the script into shots and reply with JSON only:
                {"style":"one line describing the overall visual style","scenes":[{
                  "title":"short name","description":"what the camera sees, in English, concrete and visual",
                  "narration":"the exact words spoken over this shot, in the script's language (empty if none)",
                  "dialogue":[{"character":"Name","line":"what they say"}],
                  "durationSeconds":5,
                  "cameraAngle":"WIDE|MEDIUM|CLOSE_UP|EXTREME_CLOSE_UP|OVER_THE_SHOULDER|LOW_ANGLE|HIGH_ANGLE|AERIAL|POV|TOP_DOWN",
                  "cameraMovement":"STATIC|PAN|TILT|DOLLY_IN|DOLLY_OUT|TRACKING|HANDHELD|CRANE|ORBIT|ZOOM_IN|ZOOM_OUT",
                  "lighting":"…","composition":"…","sound":"sound effects or music cues",
                  "characters":["Name"]}]}
                Keep every spoken word of the script, in order, split across the shots' narration or dialogue.
                Shots last 3–10 seconds; long narration is split over several shots. Use only the listed character names.""";
    }

    public static String scenesUser(String script, List<StoryCharacter> characters, TimelineBuilder.Format format,
                                    Project.Language language, String style) {
        StringBuilder sb = new StringBuilder();
        sb.append("Frame: ").append(switch (format) {
            case LANDSCAPE -> "16:9 landscape";
            case PORTRAIT -> "9:16 vertical";
            case SQUARE -> "1:1 square";
        }).append('\n');
        sb.append("Script language: ").append(language == Project.Language.TA ? "Tamil (keep narration and dialogue in Tamil)" : "English").append('\n');
        if (style != null && !style.isBlank()) sb.append("Wanted visual style: ").append(style).append('\n');
        if (!characters.isEmpty()) {
            sb.append("Characters:\n");
            for (StoryCharacter c : characters) sb.append("- ").append(c.visualBrief()).append('\n');
        }
        sb.append("\nScript:\n").append(script.length() > 40_000 ? script.substring(0, 40_000) : script);
        return sb.toString();
    }

    public static Plan parse(JsonNode root) {
        List<Draft> out = new ArrayList<>();
        for (JsonNode n : root.path("scenes")) {
            if (out.size() >= MAX_SCENES) break;
            String description = text(n, "description");
            String narration = text(n, "narration");
            List<Map<String, Object>> dialogue = new ArrayList<>();
            for (JsonNode d : n.path("dialogue")) {
                String who = text(d, "character");
                String line = text(d, "line");
                if (!who.isEmpty() && !line.isEmpty()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("character", who);
                    m.put("line", line);
                    dialogue.add(m);
                }
            }
            if (description.isEmpty() && narration.isEmpty() && dialogue.isEmpty()) continue;
            List<String> names = new ArrayList<>();
            for (JsonNode c : n.path("characters")) if (!c.asText("").isBlank()) names.add(c.asText().strip());
            for (Map<String, Object> d : dialogue) if (!names.contains(d.get("character").toString())) names.add(d.get("character").toString());
            double seconds = n.path("durationSeconds").asDouble(5);
            out.add(new Draft(clip(text(n, "title"), 160), description, narration, dialogue,
                    Math.max(2, Math.min(30, Double.isNaN(seconds) ? 5 : seconds)),
                    pick(text(n, "cameraAngle"), ANGLES), pick(text(n, "cameraMovement"), MOVES),
                    clip(text(n, "lighting"), 300), clip(text(n, "composition"), 300), text(n, "sound"), names));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no scenes in the reply");
        return new Plan(clip(text(root, "style"), 500), out);
    }

    /** Normalises "close-up", "Close Up" etc. to CLOSE_UP; unknown values are dropped. */
    static String pick(String value, Set<String> allowed) {
        String v = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_").replaceAll("^_|_$", "");
        return allowed.contains(v) ? v : null;
    }

    /** Matches names the AI used to saved characters, ignoring case and spacing. */
    public static List<String> matchCharacters(List<String> names, List<StoryCharacter> characters) {
        List<String> ids = new ArrayList<>();
        for (String name : names) {
            String key = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
            for (StoryCharacter c : characters) {
                if (c.getName().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip().equals(key)) {
                    String id = c.getId().toString();
                    if (!ids.contains(id)) ids.add(id);
                }
            }
        }
        return ids;
    }

    // ---- prompts for the image and video models ----------------------------------------------

    static String words(String code) {
        return code == null ? null : code.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** The still for one shot: what it shows, who is in it and how it is framed. Never asks for text in the image. */
    public static String imagePrompt(Scene s, List<StoryCharacter> cast, String style, TimelineBuilder.Format format) {
        if (s.getImagePrompt() != null && !s.getImagePrompt().isBlank()) return s.getImagePrompt().strip();
        List<String> parts = new ArrayList<>();
        if (style != null && !style.isBlank()) parts.add(style.strip());
        parts.add(s.getDescription().isBlank() ? s.getTitle() : s.getDescription().strip());
        if (!cast.isEmpty()) {
            parts.add("Characters: " + String.join("; ", cast.stream().map(StoryCharacter::visualBrief).toList())
                    + ". Match the reference images for their faces and clothing");
        }
        if (s.getCameraAngle() != null) parts.add(words(s.getCameraAngle()) + " shot");
        if (s.getLighting() != null && !s.getLighting().isBlank()) parts.add("Lighting: " + s.getLighting().strip());
        if (s.getComposition() != null && !s.getComposition().isBlank()) parts.add("Composition: " + s.getComposition().strip());
        parts.add(switch (format) {
            case LANDSCAPE -> "16:9 frame";
            case PORTRAIT -> "9:16 vertical frame";
            case SQUARE -> "square frame";
        });
        parts.add("No text, captions, logos or watermarks");
        return String.join(". ", parts) + ".";
    }

    /** The motion for one shot, for text-to-video or for animating the shot's still. */
    public static String videoPrompt(Scene s, List<StoryCharacter> cast, String style) {
        if (s.getVideoPrompt() != null && !s.getVideoPrompt().isBlank()) return s.getVideoPrompt().strip();
        List<String> parts = new ArrayList<>();
        parts.add(s.getDescription().isBlank() ? s.getTitle() : s.getDescription().strip());
        if (!cast.isEmpty()) parts.add("With " + String.join("; ", cast.stream().map(StoryCharacter::visualBrief).toList()));
        if (s.getCameraMovement() != null) parts.add("Camera: " + words(s.getCameraMovement())
                + (s.getCameraAngle() != null ? ", " + words(s.getCameraAngle()) + " shot" : ""));
        else if (s.getCameraAngle() != null) parts.add(words(s.getCameraAngle()) + " shot");
        if (s.getLighting() != null && !s.getLighting().isBlank()) parts.add("Lighting: " + s.getLighting().strip());
        if (style != null && !style.isBlank()) parts.add("Style: " + style.strip());
        parts.add("No on-screen text");
        String p = String.join(". ", parts) + ".";
        return p.length() > 3900 ? p.substring(0, 3900) : p;
    }

    /** Dialogue as "Name: line" turns, the format the voice studio reads. */
    public static String dialogueScript(List<Map<String, Object>> dialogue) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> d : dialogue) {
            sb.append(String.valueOf(d.get("character")).replace(':', ' ').strip()).append(": ")
              .append(String.valueOf(d.get("line")).replace('\n', ' ').strip()).append('\n');
        }
        return sb.toString().strip();
    }

    /** How long a shot lasts on the timeline: its planned length, or longer when what is said needs more time. */
    public static double length(double planned, double spoken) {
        return spoken <= 0 ? planned : Math.max(planned, spoken + 0.4);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("").strip();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
