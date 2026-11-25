package com.leenax.studio.videos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.videos.Videos.Plan;
import com.leenax.studio.videos.Videos.Scene;
import com.leenax.studio.videos.Videos.Segment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns a plan and its narration into an editor timeline: a video track of scene clips (drawn
 * by the lesson and quiz templates), the narration on an audio track, and captions on a text
 * track. Scene lengths follow the narration; a question waits for its countdown, then reveals
 * the answer and plays the explanation.
 */
public final class VideoTimeline {

    private VideoTimeline() {
    }

    static final ObjectMapper JSON = new ObjectMapper();
    public static final int FPS = 30;
    static final double LEAD = 0.4;
    static final double TAIL = 0.8;
    static final double MIN_SCENE = 3.0;
    static final double REVEAL_TO_ANSWER = 0.6;
    static final int CAPTION_WORDS = 9;
    static final int CAPTION_CHARS = 64;

    /** figures: drawing (SVG) by "KIND:sha256 of source", for diagrams on slides. */
    public static Map<String, Object> build(Plan p, Function<Videos.FigureRef, String> figures) {
        if (p.narration() == null) throw new ApiException(ErrorCode.CONFLICT, "Make the narration first");
        boolean vertical = "9:16".equals(p.aspect());
        int w = vertical ? 1080 : 1920, h = vertical ? 1920 : 1080;
        Map<String, Segment> seg = new HashMap<>();
        for (Segment s : Videos.list(p.narration().segments())) seg.put(s.sceneId() + "/" + s.part(), s);
        boolean captions = p.captions() == null || p.captions();
        int countdown = p.quiz() == null || p.quiz().countdown() == null ? 5 : p.quiz().countdown();

        List<Map<String, Object>> sceneClips = new ArrayList<>(), audio = new ArrayList<>(), text = new ArrayList<>();
        double t = 0;
        int n = 0;
        for (Scene s : Videos.list(p.scenes())) {
            Segment main = seg.get(s.id() + "/MAIN");
            if (main == null) throw new ApiException(ErrorCode.CONFLICT, "The narration doesn't match the scenes. Make it again.");
            ObjectNode data = JSON.valueToTree(s);
            data.remove("narration");
            data.remove("answerNarration");
            data.put("style", p.style());
            data.put("aspect", p.aspect());
            data.put("language", p.language());
            data.put("captions", captions);
            data.put("speakStart", LEAD);
            data.put("speakDuration", r3(main.duration()));
            if (s.slide() != null && s.slide().figure() != null) {
                String svg = figures.apply(new Videos.FigureRef(s.slide().figure().kind(), s.slide().figure().source()));
                if (svg == null) throw new ApiException(ErrorCode.CONFLICT, "A diagram on “" + s.title() + "” isn't drawn yet. Open its presentation once.");
                ((ObjectNode) data.path("slide").path("figure")).put("svg", svg);
            }
            if (s.slide() != null && s.slide().image() != null) data.put("imageMediaId", s.slide().image().assetId().toString());
            List<double[]> spoken = new ArrayList<>();
            spoken.add(new double[]{t + LEAD, main.start(), main.duration()});
            double dur;
            if ("QUESTION".equals(s.kind())) {
                Segment ans = seg.get(s.id() + "/ANSWER");
                if (ans == null) throw new ApiException(ErrorCode.CONFLICT, "The narration doesn't match the scenes. Make it again.");
                double cdStart = LEAD + main.duration() + 0.3;
                double reveal = cdStart + countdown;
                double ansStart = reveal + REVEAL_TO_ANSWER;
                data.put("countdownStart", r3(cdStart));
                data.put("countdown", countdown);
                data.put("revealAt", r3(reveal));
                data.put("answerStart", r3(ansStart));
                data.put("answerDuration", r3(ans.duration()));
                spoken.add(new double[]{t + ansStart, ans.start(), ans.duration()});
                dur = ansStart + ans.duration() + TAIL;
            } else {
                dur = Math.max(MIN_SCENE, LEAD + main.duration() + TAIL);
            }
            dur = r3(dur);
            data.put("number", ++n);
            data.put("total", p.scenes().size());
            Map<String, Object> clip = new LinkedHashMap<>();
            clip.put("id", "scene-" + s.id());
            clip.put("type", "scene");
            clip.put("start", r3(t));
            clip.put("duration", dur);
            clip.put("scene", data);
            sceneClips.add(clip);
            int k = 0;
            for (double[] sp : spoken) {
                String part = k == 0 ? "MAIN" : "ANSWER";
                audio.add(audioClip("a-" + s.id() + "-" + part, p.narration().mediaId().toString(), sp[0], sp[1], sp[2]));
                if (captions) {
                    String said = k == 0 ? s.narration() : s.answerNarration();
                    int i = 0;
                    for (double[] c : captionTimes(said, sp[0], sp[2])) {
                        text.add(captionClip("t-" + s.id() + "-" + part + "-" + i, chunks(said).get(i), c[0], c[1], vertical));
                        i++;
                    }
                }
                k++;
            }
            t += dur;
        }
        List<Map<String, Object>> tracks = new ArrayList<>();
        if (captions) tracks.add(track("captions", "text", "Captions", text));
        tracks.add(track("scenes", "video", "Scenes", sceneClips));
        tracks.add(track("narration", "audio", "Narration", audio));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("width", w);
        doc.put("height", h);
        doc.put("fps", FPS);
        doc.put("background", "#000000");
        doc.put("tracks", tracks);
        return doc;
    }

    /** Length of the video the plan makes, in seconds. */
    public static double duration(Map<String, Object> doc) {
        JsonNode d = JSON.valueToTree(doc);
        double end = 0;
        for (JsonNode tr : d.path("tracks")) for (JsonNode c : tr.path("clips")) end = Math.max(end, c.path("start").asDouble() + c.path("duration").asDouble());
        return r3(end);
    }

    static Map<String, Object> track(String id, String kind, String name, List<Map<String, Object>> clips) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("name", name);
        m.put("muted", false);
        m.put("hidden", false);
        m.put("locked", false);
        m.put("volume", 1);
        m.put("clips", clips);
        return m;
    }

    static Map<String, Object> audioClip(String id, String mediaId, double start, double trimStart, double duration) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("type", "media");
        c.put("mediaId", mediaId);
        c.put("kind", "AUDIO");
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
        c.put("filters", Map.of("brightness", 1, "contrast", 1, "saturation", 1, "blur", 0));
        c.put("motion", "none");
        c.put("transitionIn", Map.of("type", "none", "duration", 0.5));
        c.put("transitionOut", Map.of("type", "none", "duration", 0.5));
        return c;
    }

    static Map<String, Object> captionClip(String id, String text, double start, double duration, boolean vertical) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("type", "text");
        c.put("start", r3(start));
        c.put("duration", r3(duration));
        c.put("text", text);
        c.put("x", 0.5);
        c.put("y", vertical ? 0.88 : 0.935);
        c.put("animationIn", "none");
        c.put("animationOut", "none");
        c.put("caption", true);
        Map<String, Object> style = new LinkedHashMap<>();
        style.put("fontFamily", "Noto Sans Tamil");
        style.put("fontSize", vertical ? 52 : 44);
        style.put("fontWeight", 700);
        style.put("color", "#ffffff");
        style.put("stroke", "#000000");
        style.put("strokeWidth", 0);
        style.put("background", "rgba(0,0,0,0.6)");
        style.put("align", "center");
        style.put("width", vertical ? 0.9 : 0.86);
        style.put("shadow", false);
        c.put("style", style);
        return c;
    }

    /** Captions of up to 9 words (64 characters), breaking at sentence ends where possible. */
    static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int words = 0;
        for (String w : text.strip().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (words > 0 && (words >= CAPTION_WORDS || cur.length() + 1 + w.length() > CAPTION_CHARS)) {
                out.add(cur.toString());
                cur.setLength(0);
                words = 0;
            }
            if (words > 0) cur.append(' ');
            cur.append(w);
            words++;
            if (w.matches(".*[.?!]$") && words >= 4) {
                out.add(cur.toString());
                cur.setLength(0);
                words = 0;
            }
        }
        if (words > 0) out.add(cur.toString());
        // a word or two left over joins the caption before it, unless that ends a sentence
        if (out.size() > 1) {
            String last = out.get(out.size() - 1), prev = out.get(out.size() - 2);
            if (last.split(" ").length <= 2 && !prev.matches(".*[.?!]$") && (prev + " " + last).length() <= CAPTION_CHARS + 16) {
                out.set(out.size() - 2, prev + " " + last);
                out.remove(out.size() - 1);
            }
        }
        return out;
    }

    /** Each caption's start and length within a spoken part, in proportion to its letters. */
    static List<double[]> captionTimes(String text, double start, double duration) {
        List<String> c = chunks(text);
        int total = c.stream().mapToInt(x -> x.length() + 1).sum();
        List<double[]> out = new ArrayList<>();
        double t = start;
        for (String x : c) {
            double d = duration * (x.length() + 1) / Math.max(1, total);
            out.add(new double[]{r3(t), r3(Math.max(0.2, d))});
            t += d;
        }
        return out;
    }

    static double r3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
