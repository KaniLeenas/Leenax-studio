package com.leenax.studio.editor;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side checks of an editor document (the JSON format in
 * frontend/src/remotion/types.ts). The browser is trusted for layout, but not
 * for sizes, counts or which files a render may read.
 */
public final class TimelineDocument {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TRACKS = 30;
    public static final int MAX_CLIPS = 3000;
    public static final double MAX_SECONDS = 3 * 60 * 60;
    public static final Set<Integer> FPS = Set.of(24, 25, 30, 50, 60);

    public record Summary(int width, int height, int fps, double durationSeconds, Set<UUID> mediaIds, int clips) {
    }

    public static final class InvalidTimelineException extends RuntimeException {
        public InvalidTimelineException(String message) {
            super(message);
        }
    }

    private TimelineDocument() {
    }

    public static Summary check(JsonNode doc) {
        if (doc == null || !doc.isObject()) throw invalid("The timeline is empty");
        if (doc.path("version").asInt() != 1) throw invalid("Unknown timeline version");
        int width = doc.path("width").asInt();
        int height = doc.path("height").asInt();
        int fps = doc.path("fps").asInt();
        if (width < 64 || width > 4096 || height < 64 || height > 4096) throw invalid("Frame size must be between 64 and 4096 pixels");
        if (width % 2 != 0 || height % 2 != 0) throw invalid("Frame width and height must be even numbers");
        if (!FPS.contains(fps)) throw invalid("Frame rate must be 24, 25, 30, 50 or 60");
        if (!doc.path("background").asText("").matches("#[0-9a-fA-F]{6}")) throw invalid("Background must be a colour like #000000");

        JsonNode tracks = doc.path("tracks");
        if (!tracks.isArray()) throw invalid("Tracks are missing");
        if (tracks.size() > MAX_TRACKS) throw invalid("Use at most " + MAX_TRACKS + " tracks");
        Set<UUID> media = new LinkedHashSet<>();
        Set<String> ids = new java.util.HashSet<>();
        double end = 0;
        int clips = 0;
        for (JsonNode t : tracks) {
            String kind = t.path("kind").asText();
            if (!Set.of("video", "audio", "text").contains(kind)) throw invalid("Unknown track type: " + kind);
            if (!ids.add("t:" + t.path("id").asText())) throw invalid("Duplicate track id");
            double vol = t.path("volume").asDouble(1);
            if (vol < 0 || vol > 2) throw invalid("Track volume must be between 0 and 2");
            for (JsonNode c : t.path("clips")) {
                if (++clips > MAX_CLIPS) throw invalid("Use at most " + MAX_CLIPS + " clips");
                if (!ids.add("c:" + c.path("id").asText())) throw invalid("Duplicate clip id");
                double start = c.path("start").asDouble(-1);
                double duration = c.path("duration").asDouble(-1);
                if (start < 0 || duration <= 0 || !Double.isFinite(start + duration)) throw invalid("A clip has an invalid start or length");
                end = Math.max(end, start + duration);
                String type = c.path("type").asText();
                if ("text".equals(type)) {
                    if (!"text".equals(kind)) throw invalid("Text clips belong on text tracks");
                    if (c.path("text").asText().length() > 2000) throw invalid("Text clips can hold at most 2000 characters");
                } else if ("media".equals(type)) {
                    String mk = c.path("kind").asText();
                    boolean ok = "AUDIO".equals(mk) ? "audio".equals(kind) : ("VIDEO".equals(mk) || "IMAGE".equals(mk)) && "video".equals(kind);
                    if (!ok) throw invalid("A " + mk.toLowerCase() + " clip is on a " + kind + " track");
                    double speed = c.path("speed").asDouble(1);
                    if (speed < 0.25 || speed > 4) throw invalid("Clip speed must be between 0.25× and 4×");
                    if (c.path("trimStart").asDouble(0) < 0) throw invalid("A clip has an invalid trim");
                    double volume = c.path("volume").asDouble(1);
                    if (volume < 0 || volume > 2) throw invalid("Clip volume must be between 0 and 2");
                    try {
                        media.add(UUID.fromString(c.path("mediaId").asText()));
                    } catch (IllegalArgumentException e) {
                        throw invalid("A clip refers to an unknown file");
                    }
                } else if ("scene".equals(type)) {
                    // A lesson or quiz scene (E8): drawn by the video template; its picture, if any, is a library file.
                    if (!"video".equals(kind)) throw invalid("Scene clips belong on video tracks");
                    JsonNode scene = c.path("scene");
                    if (!scene.isObject()) throw invalid("A scene clip has no scene");
                    if (scene.toString().length() > 200_000) throw invalid("A scene is too large");
                    String image = scene.path("imageMediaId").asText("");
                    if (!image.isEmpty()) {
                        try {
                            media.add(UUID.fromString(image));
                        } catch (IllegalArgumentException e) {
                            throw invalid("A scene refers to an unknown file");
                        }
                    }
                } else {
                    throw invalid("Unknown clip type: " + type);
                }
            }
        }
        if (end > MAX_SECONDS) throw invalid("Timelines can be at most 3 hours long");
        return new Summary(width, height, fps, Math.round(end * 1000) / 1000.0, media, clips);
    }

    private static InvalidTimelineException invalid(String msg) {
        return new InvalidTimelineException(msg);
    }
}
