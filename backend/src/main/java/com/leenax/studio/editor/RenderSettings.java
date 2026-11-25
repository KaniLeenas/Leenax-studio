package com.leenax.studio.editor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Export options turned into renderer parameters.
 *
 * @param resolution short side of the output in pixels: 720, 1080, 1440 or 2160
 * @param quality    STANDARD (fast, good), HIGH (sharper, bigger file) or BEST (slow encode)
 */
public record RenderSettings(int resolution, Quality quality) {

    public enum Quality { STANDARD, HIGH, BEST }

    public static final java.util.List<Integer> RESOLUTIONS = java.util.List.of(720, 1080, 1440, 2160);

    /** Output size for a timeline: short side = resolution, both sides even, aspect kept. */
    public int[] outputSize(int width, int height) {
        double scale = scale(width, height);
        int w = (int) Math.round(width * scale);
        int h = (int) Math.round(height * scale);
        return new int[]{w - (w % 2), h - (h % 2)};
    }

    public double scale(int width, int height) {
        int shortSide = Math.min(width, height);
        double s = (double) resolution / shortSide;
        return Math.max(0.25, Math.min(2.0, s));
    }

    /**
     * H.264 settings: CRF (lower = better) and x264 preset (slower = smaller file at the same quality).
     * The output size is sent in the props (see {@link #outputSize}); the renderer itself does not scale.
     */
    public Map<String, Object> rendererParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        switch (quality) {
            case STANDARD -> {
                p.put("crf", 23);
                p.put("x264Preset", "veryfast");
            }
            case HIGH -> {
                p.put("crf", 19);
                p.put("x264Preset", "veryfast");
            }
            case BEST -> {
                p.put("crf", 18);
                p.put("x264Preset", "slow");
            }
        }
        return p;
    }

    public Map<String, Object> toMap() {
        return Map.of("resolution", resolution, "quality", quality.name());
    }

    public static RenderSettings fromMap(Map<String, Object> m) {
        int res = m.get("resolution") instanceof Number n ? n.intValue() : 1080;
        Quality q;
        try {
            q = Quality.valueOf(String.valueOf(m.getOrDefault("quality", "STANDARD")));
        } catch (IllegalArgumentException e) {
            q = Quality.STANDARD;
        }
        return new RenderSettings(res, q);
    }
}
