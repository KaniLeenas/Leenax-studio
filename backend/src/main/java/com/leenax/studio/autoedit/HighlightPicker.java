package com.leenax.studio.autoedit;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.ai.provider.MediaTypes.Word;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Short clips from a long video: the AI picks moments from the transcript; we snap them to whole words and sentences. */
public final class HighlightPicker {

    public record Highlight(String title, String hook, double start, double end) {
        public double length() {
            return end - start;
        }
    }

    private HighlightPicker() {
    }

    public static List<Highlight> parse(JsonNode node) {
        JsonNode arr = node.isArray() ? node : node.path("highlights");
        List<Highlight> out = new ArrayList<>();
        for (JsonNode h : arr) {
            double s = h.path("start").asDouble(-1);
            double e = h.path("end").asDouble(-1);
            if (s < 0 || e <= s) continue;
            String title = h.path("title").asText("").strip();
            out.add(new Highlight(title.length() > 70 ? title.substring(0, 70) : title, h.path("hook").asText("").strip(), s, e));
        }
        return out;
    }

    /**
     * Snaps each highlight to word boundaries (preferring a sentence start), keeps
     * the length within [min, max], drops overlaps and keeps at most `count`.
     */
    public static List<Highlight> snap(List<Highlight> picks, List<Word> words, double min, double max, int count) {
        List<Highlight> out = new ArrayList<>();
        if (words.isEmpty()) return out;
        for (Highlight h : picks) {
            int si = firstWordAtOrAfter(words, h.start() - 0.6);
            if (si < 0) continue;
            // prefer starting at a sentence start within the first 3 seconds
            for (int i = si; i < words.size() && words.get(i).start() < words.get(si).start() + 3; i++) {
                if (i > 0 && words.get(i - 1).text().matches(".*[.!?।]$")) {
                    si = i;
                    break;
                }
            }
            double start = words.get(si).start();
            int ei = lastWordEndingBy(words, Math.min(h.end() + 0.6, start + max));
            if (ei < si) continue;
            // prefer ending at a sentence end within the last 4 seconds
            for (int i = ei; i > si && words.get(i).end() > words.get(ei).end() - 4; i--) {
                if (words.get(i).text().matches(".*[.!?।]$") && words.get(i).end() - start >= min) {
                    ei = i;
                    break;
                }
            }
            double end = words.get(ei).end();
            while (end - start < min && ei + 1 < words.size() && words.get(ei + 1).end() - start <= max) end = words.get(++ei).end();
            if (end - start < min * 0.6) continue;
            Highlight snapped = new Highlight(h.title(), h.hook(), JumpCut.r3(Math.max(0, start - 0.1)), JumpCut.r3(end + 0.15));
            if (out.stream().noneMatch(o -> o.start() < snapped.end() && snapped.start() < o.end())) out.add(snapped);
            if (out.size() == count) break;
        }
        out.sort(Comparator.comparingDouble(Highlight::start));
        return out;
    }

    private static int firstWordAtOrAfter(List<Word> words, double t) {
        for (int i = 0; i < words.size(); i++) if (words.get(i).start() >= t) return i;
        return -1;
    }

    private static int lastWordEndingBy(List<Word> words, double t) {
        for (int i = words.size() - 1; i >= 0; i--) if (words.get(i).end() <= t) return i;
        return -1;
    }

    /** Transcript lines with timestamps for the prompt: "[12.3] words…", one line per ~sentence. */
    public static String timestamped(List<Word> words, int maxChars) {
        StringBuilder sb = new StringBuilder();
        StringBuilder line = new StringBuilder();
        double lineStart = -1;
        for (Word w : words) {
            if (lineStart < 0) lineStart = w.start();
            if (line.length() > 0) line.append(' ');
            line.append(w.text());
            if (w.text().matches(".*[.!?।]$") || line.length() > 160) {
                sb.append('[').append(String.format(java.util.Locale.ROOT, "%.1f", lineStart)).append("] ").append(line).append('\n');
                line.setLength(0);
                lineStart = -1;
                if (sb.length() > maxChars) break;
            }
        }
        if (line.length() > 0 && sb.length() <= maxChars) {
            sb.append('[').append(String.format(java.util.Locale.ROOT, "%.1f", lineStart)).append("] ").append(line).append('\n');
        }
        return sb.toString();
    }
}
