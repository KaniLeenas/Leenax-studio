package com.leenax.studio.subtitles;

import com.leenax.studio.ai.provider.MediaTypes.Segment;
import com.leenax.studio.ai.provider.MediaTypes.Word;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns word timestamps into readable subtitles: at most two lines of
 * {@code maxLineChars}, at most ~6 seconds each, breaking at sentence ends
 * and natural pauses, and never flashing by in under a second.
 */
public final class CueBuilder {

    public static final int DEFAULT_LINE_CHARS = 42;
    static final double MAX_CUE_SECONDS = 6.0;
    static final double MIN_CUE_SECONDS = 1.0;
    static final double PAUSE_BREAK_SECONDS = 0.6;

    private CueBuilder() {
    }

    public static List<Cue> fromWords(List<Word> words, int maxLineChars) {
        int lineChars = Math.max(16, maxLineChars);
        int maxChars = lineChars * 2;
        List<Cue> cues = new ArrayList<>();
        List<Word> current = new ArrayList<>();
        int chars = 0;

        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            int add = (current.isEmpty() ? 0 : 1) + w.text().length();
            boolean tooLong = !current.isEmpty() && (chars + add > maxChars || w.end() - current.get(0).start() > MAX_CUE_SECONDS);
            boolean paused = !current.isEmpty() && w.start() - current.get(current.size() - 1).end() >= PAUSE_BREAK_SECONDS;
            if (tooLong || paused) {
                cues.add(toCue(current, lineChars));
                current = new ArrayList<>();
                chars = 0;
                add = w.text().length();
            }
            current.add(w);
            chars += add;
            boolean sentenceEnd = w.text().matches(".*[.!?।…]+[\"'”’)]*$");
            if (sentenceEnd && chars >= lineChars / 2) {
                cues.add(toCue(current, lineChars));
                current = new ArrayList<>();
                chars = 0;
            }
        }
        if (!current.isEmpty()) cues.add(toCue(current, lineChars));
        return fixTiming(cues);
    }

    /** Fallback when a provider only gives segment timestamps: wrap each segment's text. */
    public static List<Cue> fromSegments(List<Segment> segments, int maxLineChars) {
        List<Cue> cues = new ArrayList<>();
        for (Segment s : segments) {
            if (s.text() == null || s.text().isBlank()) continue;
            cues.add(new Cue(s.start(), s.end(), wrap(s.text().strip(), Math.max(16, maxLineChars))));
        }
        return fixTiming(cues);
    }

    private static Cue toCue(List<Word> words, int lineChars) {
        StringBuilder sb = new StringBuilder();
        for (Word w : words) {
            if (!sb.isEmpty() && !startsWithPunctuation(w.text())) sb.append(' ');
            sb.append(w.text());
        }
        return new Cue(words.get(0).start(), words.get(words.size() - 1).end(), wrap(sb.toString(), lineChars));
    }

    private static boolean startsWithPunctuation(String s) {
        return !s.isEmpty() && ",.!?;:)”’".indexOf(s.charAt(0)) >= 0;
    }

    /** Splits text into at most two balanced lines at a space near the middle. */
    static String wrap(String text, int lineChars) {
        String t = text.replaceAll("\\s+", " ").strip();
        if (t.length() <= lineChars) return t;
        int mid = t.length() / 2;
        int best = -1;
        for (int d = 0; d < mid; d++) {
            if (mid - d > 0 && t.charAt(mid - d) == ' ') { best = mid - d; break; }
            if (mid + d < t.length() && t.charAt(mid + d) == ' ') { best = mid + d; break; }
        }
        if (best <= 0) return t;
        return t.substring(0, best) + "\n" + t.substring(best + 1);
    }

    /** Minimum on-screen time, no overlaps, strictly increasing. */
    static List<Cue> fixTiming(List<Cue> cues) {
        List<Cue> out = new ArrayList<>();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            double start = Math.max(0, c.start());
            double end = Math.max(c.end(), start + MIN_CUE_SECONDS);
            if (i + 1 < cues.size()) end = Math.min(end, Math.max(cues.get(i + 1).start(), start + 0.3));
            if (!out.isEmpty() && start < out.get(out.size() - 1).end()) start = out.get(out.size() - 1).end();
            if (end <= start) end = start + 0.3;
            out.add(new Cue(round(start), round(end), c.text()));
        }
        return out;
    }

    public static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
