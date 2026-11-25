package com.leenax.studio.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares narration text before it is sent to a voice provider:
 * pronunciation fixes, [pause] markers in the provider's own syntax, and
 * splitting long scripts into request-sized chunks at natural boundaries.
 */
public final class ScriptText {

    /** [pause], [pause 1.5s], [pause 2 s] */
    static final Pattern PAUSE = Pattern.compile("\\[pause(?:\\s+(\\d+(?:\\.\\d+)?)\\s*s?)?]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIALOGUE_LINE = Pattern.compile("^\\s*([^:：]{1,40})[:：]\\s*(.+)$");

    private ScriptText() {
    }

    public record Rule(String term, String replacement) {
    }

    public record Line(String speaker, String text) {
    }

    /** Applies "say X as Y" rules. ASCII terms match whole words, case-insensitively; other scripts match literally. */
    public static String applyPronunciation(String text, List<Rule> rules) {
        String out = text;
        for (Rule r : rules) {
            if (r.term() == null || r.term().isBlank()) continue;
            String term = r.term().strip();
            if (term.chars().allMatch(c -> c < 128)) {
                out = Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{M}\\p{N}])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(out).replaceAll(Matcher.quoteReplacement(r.replacement()));
            } else {
                out = out.replace(term, r.replacement());
            }
        }
        return out;
    }

    /** Replaces [pause …] markers using the provider's syntax. A bare [pause] is half a second. */
    public static String renderPauses(String text, DoubleFunction<String> markup) {
        Matcher m = PAUSE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            double seconds = m.group(1) != null ? Math.min(10, Double.parseDouble(m.group(1))) : 0.5;
            m.appendReplacement(sb, Matcher.quoteReplacement(" " + markup.apply(seconds) + " "));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("[ \\t]{2,}", " ").strip();
    }

    /** Removes [pause] markers (for character counting and previews). */
    public static String stripPauses(String text) {
        return PAUSE.matcher(text).replaceAll(" ").replaceAll("[ \\t]{2,}", " ").strip();
    }

    /**
     * Splits a script into chunks no longer than {@code maxChars}: by paragraph first,
     * then by sentence, then by words as a last resort.
     */
    public static List<String> chunk(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String para : text.strip().split("\\n\\s*\\n")) {
            for (String piece : splitLong(para.strip(), maxChars)) {
                if (piece.isEmpty()) continue;
                if (current.length() > 0 && current.length() + 2 + piece.length() > maxChars) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) current.append("\n\n");
                current.append(piece);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    private static List<String> splitLong(String para, int maxChars) {
        List<String> out = new ArrayList<>();
        if (para.length() <= maxChars) {
            out.add(para);
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String sentence : para.split("(?<=[.!?।…])\\s+")) {
            if (sentence.length() > maxChars) {
                if (current.length() > 0) { out.add(current.toString()); current.setLength(0); }
                StringBuilder w = new StringBuilder();
                for (String word : sentence.split("\\s+")) {
                    if (w.length() > 0 && w.length() + 1 + word.length() > maxChars) { out.add(w.toString()); w.setLength(0); }
                    if (w.length() > 0) w.append(' ');
                    w.append(word);
                }
                if (w.length() > 0) out.add(w.toString());
                continue;
            }
            if (current.length() > 0 && current.length() + 1 + sentence.length() > maxChars) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(sentence);
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    /**
     * Parses a dialogue script, one line per turn: "Name: what they say".
     * Lines without a speaker continue the previous speaker.
     */
    public static List<Line> parseDialogue(String script) {
        List<Line> out = new ArrayList<>();
        for (String raw : script.split("\\r?\\n")) {
            if (raw.isBlank()) continue;
            Matcher m = DIALOGUE_LINE.matcher(raw);
            if (m.matches() && !m.group(1).contains("[")) {
                out.add(new Line(m.group(1).strip(), m.group(2).strip()));
            } else if (!out.isEmpty()) {
                Line last = out.remove(out.size() - 1);
                out.add(new Line(last.speaker(), last.text() + " " + raw.strip()));
            }
        }
        return out;
    }
}
