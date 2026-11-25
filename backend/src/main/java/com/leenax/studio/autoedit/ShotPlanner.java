package com.leenax.studio.autoedit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which footage covers each scene. The AI suggests shots by footage
 * reference (F1, F2…) and source times; this class makes the suggestion
 * physically possible: inside the source, the right total length, no tiny
 * shots, and gaps filled with unused footage.
 */
public final class ShotPlanner {

    /** A piece of footage the planner may use. `seconds` is null for still images. */
    public record Footage(String ref, String kind, Double seconds) {
        boolean still() {
            return "IMAGE".equals(kind);
        }
    }

    /** One shot: `ref` from `sourceStart` for `length` seconds. */
    public record Shot(String ref, double sourceStart, double length) {
    }

    public record Suggestion(String ref, double start, double end) {
    }

    static final double MIN_SHOT = 1.2;
    static final double DEFAULT_STILL = 4.0;
    static final double MAX_FILL_SHOT = 6.0;

    private ShotPlanner() {
    }

    /** Reads {"scenes":[{"scene":1,"shots":[{"f":"F2","start":3.5,"end":8}]}]} by scene number (1-based). */
    public static Map<Integer, List<Suggestion>> parse(JsonNode node) {
        Map<Integer, List<Suggestion>> out = new HashMap<>();
        JsonNode arr = node.isArray() ? node : node.path("scenes");
        int i = 0;
        for (JsonNode s : arr) {
            i++;
            int n = s.path("scene").asInt(i);
            List<Suggestion> shots = new ArrayList<>();
            for (JsonNode sh : s.path("shots")) {
                String ref = sh.path("f").asText(sh.path("footage").asText(""));
                if (ref.isBlank()) continue;
                shots.add(new Suggestion(ref.strip().toUpperCase(), sh.path("start").asDouble(0), sh.path("end").asDouble(-1)));
            }
            out.put(n, shots);
        }
        return out;
    }

    /**
     * Turns suggestions into shots that exactly fill each scene's duration.
     *
     * @param durations seconds of narration per scene
     */
    public static List<List<Shot>> plan(List<Double> durations, List<Footage> footage, Map<Integer, List<Suggestion>> suggestions) {
        Map<String, Footage> byRef = new HashMap<>();
        for (Footage f : footage) byRef.put(f.ref(), f);
        Map<String, Double> cursor = new HashMap<>(); // where fill-in footage continues from
        int[] rr = {0};
        List<List<Shot>> out = new ArrayList<>();
        for (int s = 0; s < durations.size(); s++) {
            double need = durations.get(s);
            List<Shot> shots = new ArrayList<>();
            double have = 0;
            for (Suggestion sug : suggestions.getOrDefault(s + 1, List.of())) {
                if (have >= need - 0.05) break;
                Footage f = byRef.get(sug.ref());
                if (f == null) continue;
                double remaining = need - have;
                Shot shot = fit(f, sug.start(), sug.end(), remaining);
                if (shot == null) continue;
                // avoid a sliver at the end: stretch this shot instead if possible
                if (remaining - shot.length() > 0 && remaining - shot.length() < MIN_SHOT) shot = stretch(f, shot, remaining);
                shots.add(shot);
                have += shot.length();
                cursor.put(f.ref(), shot.sourceStart() + shot.length());
            }
            while (have < need - 0.05 && !footage.isEmpty()) {
                Footage f = footage.get(rr[0]++ % footage.size());
                double remaining = need - have;
                double len = Math.min(remaining, MAX_FILL_SHOT);
                if (remaining - len > 0 && remaining - len < MIN_SHOT) len = remaining;
                double from = cursor.getOrDefault(f.ref(), 0.0);
                if (!f.still() && f.seconds() != null) {
                    if (from + Math.min(len, f.seconds()) > f.seconds()) from = 0; // wrap around
                    len = Math.min(len, f.seconds());
                    if (len < 0.2) continue;
                }
                Shot shot = new Shot(f.ref(), f.still() ? 0 : from, r3(len));
                shots.add(shot);
                have += shot.length();
                cursor.put(f.ref(), from + len);
            }
            out.add(shots);
        }
        return out;
    }

    static Shot fit(Footage f, double start, double end, double maxLength) {
        if (f.still()) {
            double want = end > start ? end - start : DEFAULT_STILL;
            return new Shot(f.ref(), 0, r3(Math.min(maxLength, Math.max(MIN_SHOT, want))));
        }
        double dur = f.seconds() == null ? 0 : f.seconds();
        if (dur < 0.3) return null;
        double s = Math.max(0, Math.min(start, dur - 0.3));
        double e = end > s ? Math.min(end, dur) : Math.min(dur, s + 4);
        double len = Math.min(e - s, maxLength);
        if (len < Math.min(MIN_SHOT, maxLength)) {
            // too short as suggested: take more of the same footage if it has it
            len = Math.min(maxLength, Math.min(dur - s, MIN_SHOT));
            if (len < 0.3) return null;
        }
        return new Shot(f.ref(), r3(s), r3(len));
    }

    private static Shot stretch(Footage f, Shot shot, double target) {
        if (f.still()) return new Shot(shot.ref(), 0, r3(target));
        double max = f.seconds() == null ? shot.length() : f.seconds() - shot.sourceStart();
        return new Shot(shot.ref(), shot.sourceStart(), r3(Math.min(target, max)));
    }

    static double r3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
