package com.leenax.studio.youtube;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Caption files for YouTube (E9), made from the captions of the render that was checked: the same
 * words at the same times as the approved video, so nothing is retyped or retimed.
 */
public final class Srt {

    private Srt() {
    }

    public record Cue(double start, double end, String text) {
    }

    /** Caption clips of a rendered timeline document (text clips marked caption), in time order. */
    public static List<Cue> cues(JsonNode document) {
        List<Cue> out = new ArrayList<>();
        for (JsonNode track : document.path("tracks")) {
            for (JsonNode clip : track.path("clips")) {
                if (!"text".equals(clip.path("type").asText()) || !clip.path("caption").asBoolean(false)) continue;
                String text = clip.path("text").asText("").replaceAll("\\s+", " ").strip();
                double start = clip.path("start").asDouble(-1), dur = clip.path("duration").asDouble(0);
                if (text.isEmpty() || start < 0 || dur <= 0) continue;
                out.add(new Cue(start, start + dur, text));
            }
        }
        out.sort(Comparator.comparingDouble(Cue::start));
        // YouTube shows overlapping cues stacked; end each one where the next begins
        List<Cue> fixed = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            Cue c = out.get(i);
            double end = i + 1 < out.size() ? Math.min(c.end(), out.get(i + 1).start()) : c.end();
            if (end - c.start() >= 0.05) fixed.add(new Cue(c.start(), end, c.text()));
        }
        return fixed;
    }

    public static String write(List<Cue> cues) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Cue c : cues) {
            b.append(++n).append('\n').append(time(c.start())).append(" --> ").append(time(c.end())).append('\n')
                    .append(c.text()).append("\n\n");
        }
        return b.toString();
    }

    static String time(double seconds) {
        long ms = Math.round(Math.max(0, seconds) * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    /** The video's language code for YouTube (the plan uses EN and TA). */
    public static String language(String planLanguage) {
        return "TA".equalsIgnoreCase(planLanguage) ? "ta" : "en";
    }

    public static String trackName(String planLanguage) {
        return "TA".equalsIgnoreCase(planLanguage) ? "தமிழ்" : "English";
    }
}
