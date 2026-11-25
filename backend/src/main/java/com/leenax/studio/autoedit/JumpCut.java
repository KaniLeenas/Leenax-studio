package com.leenax.studio.autoedit;

import com.leenax.studio.ai.provider.MediaTypes.Word;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Talking-head clean-up from word timestamps: keeps the parts where you speak,
 * cuts long pauses and (optionally) filler words, and leaves a little air
 * around each phrase so cuts don't clip syllables.
 */
public final class JumpCut {

    public record Range(double start, double end) {
        public double length() {
            return end - start;
        }
    }

    /**
     * @param maxPause   pauses longer than this (seconds) are cut
     * @param padding    kept before the first and after the last word of each phrase
     * @param minRange   phrases shorter than this are dropped (stray noises)
     */
    public record Options(double maxPause, double padding, double minRange, boolean removeFillers, Set<String> fillers) {
        public static Options defaults() {
            return new Options(0.45, 0.12, 0.3, true, DEFAULT_FILLERS);
        }
    }

    /** Common English and Tamil fillers, matched as whole words after removing punctuation. */
    public static final Set<String> DEFAULT_FILLERS = Set.of(
            "um", "umm", "uh", "uhh", "uhm", "erm", "er", "ah", "ahh", "hmm", "mm", "mmm",
            "ம்", "ம்ம்", "ம்ம்ம்", "அ", "ஆ", "ஏ", "ஹ்ம்");

    private JumpCut() {
    }

    public static String normalise(String word) {
        return word.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}“”‘’…]", "").strip();
    }

    public static List<Range> keepRanges(List<Word> words, double sourceDuration, Options o) {
        List<Word> kept = new ArrayList<>();
        for (Word w : words) {
            if (w.end() <= w.start()) continue;
            if (o.removeFillers() && o.fillers().contains(normalise(w.text()))) continue;
            kept.add(w);
        }
        List<Range> ranges = new ArrayList<>();
        double start = -1;
        double end = -1;
        for (Word w : kept) {
            if (start < 0) {
                start = w.start();
                end = w.end();
            } else if (w.start() - end <= o.maxPause()) {
                end = Math.max(end, w.end());
            } else {
                add(ranges, start, end, sourceDuration, o);
                start = w.start();
                end = w.end();
            }
        }
        if (start >= 0) add(ranges, start, end, sourceDuration, o);
        return merge(ranges);
    }

    private static void add(List<Range> out, double start, double end, double duration, Options o) {
        double s = Math.max(0, start - o.padding());
        double e = duration > 0 ? Math.min(duration, end + o.padding()) : end + o.padding();
        if (e - s >= o.minRange()) out.add(new Range(r3(s), r3(e)));
    }

    /** Padding can make neighbours touch; join them so there are no zero-length cuts. */
    static List<Range> merge(List<Range> ranges) {
        List<Range> out = new ArrayList<>();
        for (Range r : ranges) {
            if (!out.isEmpty() && r.start() <= out.get(out.size() - 1).end() + 0.05) {
                Range last = out.remove(out.size() - 1);
                out.add(new Range(last.start(), Math.max(last.end(), r.end())));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    /** Word positions after the cuts: each kept range is placed right after the previous one. */
    public static List<Word> retime(List<Word> words, List<Range> ranges, double offset) {
        List<Word> out = new ArrayList<>();
        double cursor = offset;
        for (Range r : ranges) {
            for (Word w : words) {
                if (w.start() >= r.start() - 1e-6 && w.end() <= r.end() + 1e-6) {
                    out.add(new Word(w.text(), r3(cursor + w.start() - r.start()), r3(cursor + w.end() - r.start())));
                }
            }
            cursor += r.length();
        }
        return out;
    }

    public static double total(List<Range> ranges) {
        return r3(ranges.stream().mapToDouble(Range::length).sum());
    }

    static double r3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
