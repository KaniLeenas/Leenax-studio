package com.leenax.studio.documents;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words, passages and search snippets for course documents. Works the same for
 * English and Tamil: a word is letters plus combining marks plus digits, so Tamil
 * vowel signs and pulli stay inside their word.
 *
 * Search matches word beginnings: "normal" finds "normalisation", and "கணினி"
 * finds "கணினியின்". That suits Tamil, where endings attach to the word, without
 * needing a Tamil stemmer.
 */
public final class DocumentText {

    /** One page as the document service read it. */
    public record PageText(int pageNo, String text) {
    }

    /** A passage of one page; passages never cross pages. */
    public record Chunk(int pageNo, int ordinal, String text) {
    }

    /** Offsets into {@link Snippet#text()}, end exclusive, in UTF-16 units (as JavaScript counts). */
    public record Highlight(int start, int end) {
    }

    public record Snippet(String text, boolean cutBefore, boolean cutAfter, List<Highlight> highlights) {
    }

    static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    static final int TARGET_CHARS = 1000;
    static final int MAX_CHARS = 1500;
    static final int MAX_QUERY_WORDS = 8;
    static final int SNIPPET_CHARS = 260;
    static final int SNIPPET_LEAD = 80;

    private DocumentText() {
    }

    // ---- words -------------------------------------------------------------------------------------

    static String norm(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** Every word, normalised, in order (repeats kept). */
    public static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = WORD.matcher(Normalizer.normalize(text, Normalizer.Form.NFC));
        while (m.find()) out.add(norm(m.group()));
        return out;
    }

    /** The distinct words of a passage, for the search index. Single characters are left out. */
    public static String[] terms(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : words(text)) {
            if (w.codePointCount(0, w.length()) > 1) out.add(w);
        }
        return out.toArray(String[]::new);
    }

    /** What to look for: distinct words of at least two characters, at most eight. */
    public static List<String> queryWords(String query) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : words(query)) {
            if (w.codePointCount(0, w.length()) > 1) out.add(w);
            if (out.size() == MAX_QUERY_WORDS) break;
        }
        return List.copyOf(out);
    }

    // ---- passages ----------------------------------------------------------------------------------

    /** Cuts pages into passages of about a thousand characters, along paragraph and sentence breaks. */
    public static List<Chunk> chunk(List<PageText> pages) {
        List<Chunk> out = new ArrayList<>();
        int ordinal = 0;
        for (PageText page : pages) {
            if (page.text() == null || page.text().isBlank()) continue;
            StringBuilder buf = new StringBuilder();
            for (String piece : pieces(page.text())) {
                if (!buf.isEmpty() && buf.length() + piece.length() + 2 > TARGET_CHARS) {
                    out.add(new Chunk(page.pageNo(), ordinal++, buf.toString()));
                    buf.setLength(0);
                }
                if (!buf.isEmpty()) buf.append("\n\n");
                buf.append(piece);
            }
            if (!buf.isEmpty()) out.add(new Chunk(page.pageNo(), ordinal++, buf.toString()));
        }
        return out;
    }

    /** Paragraphs, with any paragraph longer than MAX_CHARS split at sentence ends, then at spaces. */
    static List<String> pieces(String text) {
        List<String> out = new ArrayList<>();
        for (String para : text.strip().split("\\n\\s*\\n")) {
            para = para.strip();
            if (para.isEmpty()) continue;
            while (para.length() > MAX_CHARS) {
                int cut = lastBreak(para, MAX_CHARS);
                out.add(para.substring(0, cut).strip());
                para = para.substring(cut).strip();
            }
            if (!para.isEmpty()) out.add(para);
        }
        return out;
    }

    private static int lastBreak(String s, int limit) {
        int floor = limit / 2;
        for (int i = limit; i > floor; i--) {
            char c = s.charAt(i - 1);
            if ((c == '.' || c == '?' || c == '!' || c == '\n') && Character.isWhitespace(s.charAt(i))) return i;
        }
        for (int i = limit; i > floor; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        // No break at all (a very long token): never cut inside a surrogate pair or before a combining mark.
        int i = limit;
        while (i > 1 && (Character.isLowSurrogate(s.charAt(i)) || isMark(s.codePointAt(i)))) i--;
        return i;
    }

    private static boolean isMark(int cp) {
        int t = Character.getType(cp);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK;
    }

    // ---- ranking and snippets ----------------------------------------------------------------------

    static boolean matches(String word, List<String> query) {
        for (String q : query) {
            if (word.startsWith(q)) return true;
        }
        return false;
    }

    /** Higher is better: words matched (whole words count double), plus one for every query word present. */
    public static int score(String text, List<String> query) {
        int score = 0;
        Set<String> found = new LinkedHashSet<>();
        for (String w : words(text)) {
            for (String q : query) {
                if (w.startsWith(q)) {
                    score += w.equals(q) ? 2 : 1;
                    found.add(q);
                }
            }
        }
        return score + found.size() * 5;
    }

    /** A window around the first match, with every matching word marked. */
    public static Snippet snippet(String text, List<String> query) {
        String t = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip();
        Matcher m = WORD.matcher(t);
        int first = -1;
        while (m.find()) {
            if (matches(norm(m.group()), query)) {
                first = m.start();
                break;
            }
        }
        int start = first < 0 ? 0 : Math.max(0, first - SNIPPET_LEAD);
        if (start > 0) {
            int space = t.indexOf(' ', start);
            start = space < 0 || space >= first ? start : space + 1;
        }
        int end = Math.min(t.length(), start + SNIPPET_CHARS);
        if (end < t.length()) {
            int space = t.lastIndexOf(' ', end);
            if (space > start + SNIPPET_CHARS / 2) end = space;
        }
        String window = t.substring(start, end);
        List<Highlight> marks = new ArrayList<>();
        Matcher w = WORD.matcher(window);
        while (w.find()) {
            if (matches(norm(w.group()), query)) marks.add(new Highlight(w.start(), w.end()));
        }
        return new Snippet(window, start > 0, end < t.length(), marks);
    }
}
