package com.leenax.studio.images;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** FFmpeg resize/crop for social formats. */
public final class ImageRecipes {

    public enum Fit {
        /** Fill the frame, cropping the overflow from the centre. */
        COVER,
        /** Fit the whole image inside the frame and pad the rest. */
        CONTAIN
    }

    private ImageRecipes() {
    }

    /**
     * @param background hex colour like "#000000" for CONTAIN padding, or null for transparent (PNG only)
     */
    public static List<String> resize(Path in, Path out, int width, int height, Fit fit, String background) {
        String filter;
        if (fit == Fit.COVER) {
            filter = String.format(Locale.ROOT, "scale=%d:%d:force_original_aspect_ratio=increase:flags=lanczos,crop=%d:%d", width, height, width, height);
        } else {
            boolean png = out.toString().toLowerCase(Locale.ROOT).endsWith(".png");
            String colour = background != null && background.matches("#[0-9a-fA-F]{6}") ? "0x" + background.substring(1)
                    : (png ? "black@0" : "black");
            filter = String.format(Locale.ROOT,
                    "scale=%d:%d:force_original_aspect_ratio=decrease:flags=lanczos,format=rgba,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:color=%s",
                    width, height, width, height, colour);
        }
        boolean jpg = out.toString().toLowerCase(Locale.ROOT).endsWith(".jpg");
        return jpg
                ? List.of("-i", in.toString(), "-vf", filter + ",format=yuvj444p", "-frames:v", "1", "-q:v", "2", out.toString())
                : List.of("-i", in.toString(), "-vf", filter, "-frames:v", "1", out.toString());
    }

    /** Frame presets offered in the UI. */
    public record Preset(String id, String label, int width, int height) {
    }

    public static final List<Preset> PRESETS = List.of(
            new Preset("youtube-thumbnail", "YouTube thumbnail", 1280, 720),
            new Preset("youtube-banner", "YouTube channel banner", 2560, 1440),
            new Preset("instagram-square", "Instagram post (square)", 1080, 1080),
            new Preset("instagram-portrait", "Instagram post (portrait)", 1080, 1350),
            new Preset("story", "Story / Reel / Short cover", 1080, 1920),
            new Preset("facebook-link", "Facebook / link preview", 1200, 630),
            new Preset("x-post", "X post", 1600, 900));
}
