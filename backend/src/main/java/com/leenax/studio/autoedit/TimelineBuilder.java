package com.leenax.studio.autoedit;

import com.leenax.studio.subtitles.Cue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds editor documents (the Phase 4 timeline format, see frontend/src/remotion/types.ts)
 * so automatic edits open in the normal editor and stay fully editable.
 */
public final class TimelineBuilder {

    public enum Format {
        LANDSCAPE(1920, 1080), PORTRAIT(1080, 1920), SQUARE(1080, 1080);

        public final int width;
        public final int height;

        Format(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public enum TrackKind { VIDEO, AUDIO, TEXT }

    private final Format format;
    private final int fps;
    private final List<Map<String, Object>> tracks = new ArrayList<>();
    private int ids;

    public TimelineBuilder(Format format, int fps) {
        this.format = format;
        this.fps = fps;
    }

    public Format format() {
        return format;
    }

    /** Adds a track. Tracks added first are drawn in front (index 0 is the top track). */
    public Map<String, Object> track(TrackKind kind, String name, double volume) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id("t"));
        t.put("kind", kind.name().toLowerCase());
        t.put("name", name);
        t.put("muted", false);
        t.put("hidden", false);
        t.put("locked", false);
        t.put("volume", volume);
        t.put("clips", new ArrayList<Map<String, Object>>());
        tracks.add(t);
        return t;
    }

    /** Video, image or audio clip with editor defaults; returns it so callers can adjust fields. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> media(Map<String, Object> track, UUID mediaId, String kind, double start, double duration, double trimStart) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id("c"));
        c.put("type", "media");
        c.put("mediaId", mediaId.toString());
        c.put("kind", kind);
        c.put("start", r3(start));
        c.put("duration", r3(duration));
        c.put("trimStart", r3(trimStart));
        c.put("speed", 1);
        c.put("volume", 1);
        c.put("fadeIn", 0);
        c.put("fadeOut", 0);
        c.put("fit", "cover");
        c.put("x", 0);
        c.put("y", 0);
        c.put("scale", 1);
        c.put("rotation", 0);
        c.put("opacity", 1);
        c.put("filters", new LinkedHashMap<>(Map.of("brightness", 1, "contrast", 1, "saturation", 1, "blur", 0)));
        c.put("motion", "none");
        c.put("transitionIn", new LinkedHashMap<>(Map.of("type", "none", "duration", 0.5)));
        c.put("transitionOut", new LinkedHashMap<>(Map.of("type", "none", "duration", 0.5)));
        ((List<Map<String, Object>>) track.get("clips")).add(c);
        return c;
    }

    public enum TextLook { TITLE, CAPTION, SHORTS_CAPTION, LOWER_THIRD }

    @SuppressWarnings("unchecked")
    public Map<String, Object> text(Map<String, Object> track, String text, double start, double duration, TextLook look) {
        boolean portrait = format == Format.PORTRAIT;
        int base = Math.min(format.width, format.height);
        Map<String, Object> style = new LinkedHashMap<>();
        style.put("fontFamily", "Anek Tamil Variable");
        style.put("color", "#ffffff");
        style.put("stroke", "#000000");
        style.put("align", "center");
        style.put("shadow", true);
        style.put("background", "");
        double x = 0.5;
        double y;
        String in;
        String out;
        switch (look) {
            case TITLE -> {
                style.put("fontSize", (int) (base * 0.105));
                style.put("fontWeight", 800);
                style.put("strokeWidth", 6);
                style.put("width", 0.9);
                y = portrait ? 0.32 : 0.45;
                in = "pop";
                out = "fade";
            }
            case SHORTS_CAPTION -> {
                style.put("fontSize", (int) (base * 0.075));
                style.put("fontWeight", 800);
                style.put("strokeWidth", 5);
                style.put("width", 0.88);
                style.put("color", "#ffffff");
                y = 0.68;
                in = "pop";
                out = "none";
            }
            case LOWER_THIRD -> {
                style.put("fontSize", (int) (base * 0.05));
                style.put("fontWeight", 700);
                style.put("strokeWidth", 0);
                style.put("color", "#14182e");
                style.put("background", "#f5a524");
                style.put("align", "left");
                style.put("shadow", false);
                style.put("width", 0.6);
                x = 0.35;
                y = 0.82;
                in = "slide-up";
                out = "fade";
            }
            default -> { // CAPTION
                style.put("fontSize", (int) (base * 0.05));
                style.put("fontWeight", 700);
                style.put("strokeWidth", 0);
                style.put("background", "rgba(0,0,0,0.55)");
                style.put("shadow", false);
                style.put("width", portrait ? 0.86 : 0.8);
                y = portrait ? 0.72 : 0.86;
                in = "none";
                out = "none";
            }
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id("c"));
        c.put("type", "text");
        c.put("start", r3(start));
        c.put("duration", r3(Math.max(0.1, duration)));
        c.put("text", text);
        c.put("x", x);
        c.put("y", y);
        c.put("animationIn", in);
        c.put("animationOut", out);
        c.put("style", style);
        if (look == TextLook.CAPTION || look == TextLook.SHORTS_CAPTION) c.put("caption", true);
        ((List<Map<String, Object>>) track.get("clips")).add(c);
        return c;
    }

    public void captions(Map<String, Object> track, List<Cue> cues, boolean shorts) {
        for (Cue cue : cues) {
            if (cue.end() - cue.start() < 0.05 || cue.text().isBlank()) continue;
            text(track, cue.text(), cue.start(), cue.end() - cue.start(), shorts ? TextLook.SHORTS_CAPTION : TextLook.CAPTION);
        }
    }

    /**
     * Music under the whole edit: repeats the track if it is shorter than the video,
     * fades in at the start and out at the end.
     */
    public void music(Map<String, Object> track, UUID mediaId, double musicSeconds, double totalSeconds, double volume) {
        if (musicSeconds <= 0.5 || totalSeconds <= 0) return;
        double t = 0;
        Map<String, Object> last = null;
        while (t < totalSeconds - 0.05) {
            double d = Math.min(musicSeconds, totalSeconds - t);
            last = media(track, mediaId, "AUDIO", t, d, 0);
            last.put("volume", volume);
            if (t == 0) last.put("fadeIn", Math.min(1.5, d / 3));
            t += d;
        }
        if (last != null) last.put("fadeOut", Math.min(2.5, ((Number) last.get("duration")).doubleValue() / 3));
    }

    public Map<String, Object> build() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("width", format.width);
        doc.put("height", format.height);
        doc.put("fps", fps);
        doc.put("background", "#000000");
        doc.put("tracks", tracks);
        return doc;
    }

    private String id(String prefix) {
        return prefix + "ae" + Integer.toString(++ids, 36);
    }

    static double r3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
