package com.leenax.studio.seo;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.documents.DocumentText;
import com.leenax.studio.qa.QaRules;
import com.leenax.studio.seo.Seo.Metric;
import com.leenax.studio.social.YouTubeClient.VideoInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The SEO rules that need no AI: labelled numbers, the opportunity score, title checks, Trends files. */
public final class SeoLogic {

    private SeoLogic() {
    }

    // ---- every number has a source or an "estimated" label -----------------------------------------

    /** Keys whose numbers are positions, not measurements. */
    static final Set<String> STRUCTURAL = Set.of("rank");

    /**
     * Paths of numbers that are neither a labelled Metric's value nor a position. A Metric is
     * any object with a "value" and either a non-blank "source" or "estimated": true.
     */
    public static List<String> unlabelledNumbers(JsonNode node) {
        List<String> out = new ArrayList<>();
        walk(node, "$", null, out);
        return out;
    }

    private static void walk(JsonNode n, String path, String key, List<String> out) {
        if (n == null) return;
        if (n.isNumber()) {
            if (key == null || !STRUCTURAL.contains(key)) out.add(path);
            return;
        }
        if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) walk(n.get(i), path + "[" + i + "]", null, out);
            return;
        }
        if (!n.isObject()) return;
        if (n.has("value") && (n.get("value").isNumber() || n.get("value").isNull())) {
            boolean labelled = (n.path("source").isTextual() && !n.path("source").asText().isBlank()) || n.path("estimated").asBoolean(false);
            if (n.get("value").isNumber() && !labelled) out.add(path + ".value");
            if (n.get("value").isNull() && !n.path("note").isTextual() && !labelled) out.add(path + ".value (empty without a reason)");
            n.fields().forEachRemaining(e -> {
                if (!e.getKey().equals("value")) walk(e.getValue(), path + "." + e.getKey(), e.getKey(), out);
            });
            return;
        }
        n.fields().forEachRemaining(e -> walk(e.getValue(), path + "." + e.getKey(), e.getKey(), out));
    }

    // ---- the keyword opportunity score (specification, section 8) ----------------------------------

    /** Settings: the weights of D, C and G, and what counts as recent, long and highly viewed. */
    public record Weights(double demand, double competition, double gap, int recentDays, int longSeconds, long manyViews) {
        public static Weights defaults() {
            return new Weights(0.4, 0.3, 0.3, 365, 480, 100_000);
        }
    }

    public static final String SCORE_METHOD = "Opportunity = R × (wD·D + wC·(1 − C) + wG·G). R: share of the keyword's words found in the "
            + "approved topic, its subtopics and notes. D: recent Google Trends interest ÷ 100, when a Trends file has the term. C: competition "
            + "among the top results (recent, long, highly viewed). G: share of top results that don't cover the keyword. Without D, the "
            + "other weights become 0.5 and 0.5 and the score is partial. An estimate, not a search volume.";

    public static Metric relevance(String keyword, String topicText) {
        List<String> words = significant(keyword);
        if (words.isEmpty()) return Metric.estimate(0, "0-1", "No usable words in the keyword");
        Set<String> have = new java.util.HashSet<>(DocumentText.words(topicText));
        long found = words.stream().filter(w -> have.contains(w) || have.stream().anyMatch(h -> h.startsWith(w) || w.startsWith(h) && h.length() > 3)).count();
        return Metric.estimate(round((double) found / words.size()), "0-1",
                found + " of " + words.size() + " words found in the approved topic, its subtopics and notes");
    }

    /** C: for each top result, recent + long + highly viewed, each 0 or 1, averaged. */
    public static Metric competition(List<VideoInfo> results, Instant now, Weights w, String retrievedAt) {
        if (results.isEmpty()) return null;
        int recent = 0, longer = 0, viewed = 0;
        for (VideoInfo v : results) {
            Instant at = parse(v.publishedAt());
            if (at != null && at.isAfter(now.minusSeconds(86_400L * w.recentDays()))) recent++;
            if (v.durationSeconds() != null && v.durationSeconds() >= w.longSeconds()) longer++;
            if (v.views() != null && v.views() >= w.manyViews()) viewed++;
        }
        int n = results.size();
        double c = (recent + longer + viewed) / (3.0 * n);
        Map<String, Metric> in = new LinkedHashMap<>();
        in.put("results", Metric.of(n, "videos", Seo.YT_SEARCH, retrievedAt));
        in.put("recent", Metric.counted(recent, "videos published in the last " + w.recentDays() + " days"));
        in.put("long", Metric.counted(longer, "videos at least " + (w.longSeconds() / 60) + " minutes long"));
        in.put("highlyViewed", Metric.counted(viewed, "videos with at least " + w.manyViews() + " public views"));
        return Metric.estimate(round(c), "0-1", "(recent + long + highly viewed) ÷ (3 × results)").withInputs(in);
    }

    /** G: share of the top results whose title and description don't cover the words. */
    public static Metric gap(String words, List<VideoInfo> results) {
        if (results.isEmpty()) return null;
        int missing = 0;
        for (VideoInfo v : results) if (!QaRules.covered(words, v.title() + "\n" + v.description())) missing++;
        return Metric.estimate(round((double) missing / results.size()), "0-1",
                missing + " of " + results.size() + " top results don't cover it in their title or description");
    }

    /** D from a Trends series: the mean of the last (up to) 12 points ÷ 100. */
    public static Metric demand(List<Double> series, String filename, String importedAt, String geo) {
        if (series == null || series.isEmpty()) return null;
        List<Double> tail = series.subList(Math.max(0, series.size() - 12), series.size());
        double mean = tail.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new Metric(round(mean / 100.0), "0-1", "Google Trends CSV “" + filename + "”" + (geo == null ? "" : ", " + geo), importedAt, true,
                "Mean of the last " + tail.size() + " points ÷ 100. Trends values are relative (0 to 100 within that download), not volumes",
                null, null);
    }

    public static Metric score(Metric r, Metric d, Metric c, Metric g, Weights w) {
        if (r == null || r.value() == null || c == null || g == null) return null;
        double inner;
        Map<String, Metric> in = new LinkedHashMap<>();
        in.put("R", r);
        boolean partial = d == null || d.value() == null;
        if (!partial) {
            double sum = w.demand() + w.competition() + w.gap();
            inner = (w.demand() * d.value() + w.competition() * (1 - c.value()) + w.gap() * g.value()) / sum;
            in.put("D", d);
            in.put("wD", Metric.of(w.demand() / sum, "weight", Seo.SETTING, null));
            in.put("wC", Metric.of(w.competition() / sum, "weight", Seo.SETTING, null));
            in.put("wG", Metric.of(w.gap() / sum, "weight", Seo.SETTING, null));
        } else {
            inner = 0.5 * (1 - c.value()) + 0.5 * g.value();
            in.put("wC", Metric.of(0.5, "weight", "Specification: without D", null));
            in.put("wG", Metric.of(0.5, "weight", "Specification: without D", null));
        }
        in.put("C", c);
        in.put("G", g);
        return new Metric(round(r.value() * inner), "0-1", null, null, true, SCORE_METHOD, partial ? "Partial: no Trends value" : null, in);
    }

    // ---- titles ------------------------------------------------------------------------------------

    static final Pattern CLICKBAIT = Pattern.compile("(?iU)(you won'?t believe|shocking|mind[- ]?blowing|guaranteed|100\\s*%|secret|exposed|"
            + "never seen before|insane|unbelievable|!!|\\?\\?|பிரமிப்பூட்டும்|அதிர்ச்சி)");
    static final Pattern SHOUT = Pattern.compile("\\b\\p{Lu}{5,}\\b");

    public static Seo.TitleCheck checkTitle(String text, List<String> keywords, Map<String, Metric> ai, Metric overall, String reasoning) {
        int len = text == null ? 0 : text.codePointCount(0, text.length());
        List<String> warn = new ArrayList<>();
        if (len == 0) warn.add("Empty");
        if (len > Seo.TITLE_MAX) warn.add("Longer than YouTube allows (" + Seo.TITLE_MAX + " characters)");
        else if (len > Seo.TITLE_SHOWN) warn.add("May be cut off in search results after about " + Seo.TITLE_SHOWN + " characters");
        if (text != null && CLICKBAIT.matcher(text).find()) warn.add("Sounds like clickbait");
        if (text != null && SHOUT.matcher(text).find() && !text.matches(".*\\b(MCQ|MCQs|SQL|DBMS|ACID|BCNF|HTML|HTTP|NoSQL)\\b.*")) {
            warn.add("Shouting in capitals");
        }
        boolean kw = text != null && keywords.stream().anyMatch(k -> QaRules.covered(k, text));
        if (!kw && !keywords.isEmpty()) warn.add("Has none of the primary keywords");
        return new Seo.TitleCheck(hash(text), Metric.counted(len, "characters"), keywords.isEmpty() ? null : kw, warn, ai, overall, reasoning);
    }

    /** A title edited after scoring keeps its rule checks (made again) but loses the AI's scores. */
    public static Seo.TitleCheck recheck(String text, Seo.TitleCheck old, List<String> keywords) {
        boolean same = old != null && hash(text).equals(old.textHash());
        Seo.TitleCheck c = checkTitle(text, keywords, same ? old.ai() : null, same ? old.overall() : null, same ? old.reasoning() : null);
        if (!same && old != null && old.ai() != null) {
            List<String> w = new ArrayList<>(c.warnings());
            w.add("Edited after the AI scored it: not scored");
            c = new Seo.TitleCheck(c.textHash(), c.length(), c.hasKeyword(), w, null, null, null);
        }
        return c;
    }

    public static boolean usable(Seo.TitleCheck c) {
        return c != null && c.warnings().stream().noneMatch(w -> w.startsWith("Empty") || w.startsWith("Longer than YouTube") || w.startsWith("Sounds like clickbait"));
    }

    // ---- Google Trends CSV (downloaded from the Trends website) ------------------------------------

    public record Trends(String geo, String period, LocalDate first, LocalDate last, List<String> terms, Map<String, List<Double>> series) {
    }

    /**
     * Reads an "Interest over time" CSV from trends.google.com: optional "Category:" lines, then a
     * header "Week|Day|Month|Time, term: (Region), ..." and rows "2025-11-23,45,<1". "<1" is read as 0.5.
     */
    public static Trends parseTrends(String csv) {
        if (csv == null || csv.isBlank()) throw ApiException.badRequest("The file is empty");
        String[] lines = csv.replace("﻿", "").split("\\r?\\n");
        int h = -1;
        for (int i = 0; i < lines.length && i < 10; i++) {
            String first = cells(lines[i]).get(0).strip().toLowerCase(Locale.ROOT);
            if (first.equals("week") || first.equals("day") || first.equals("month") || first.equals("time") || first.equals("date")) {
                h = i;
                break;
            }
        }
        if (h < 0) throw ApiException.badRequest("This isn't an “Interest over time” CSV from Google Trends (no Week, Day or Month column)");
        List<String> header = cells(lines[h]);
        if (header.size() < 2) throw ApiException.badRequest("The Trends file has no search terms");
        String period = switch (header.get(0).strip().toLowerCase(Locale.ROOT)) {
            case "week" -> "WEEK";
            case "day" -> "DAY";
            case "month" -> "MONTH";
            default -> "OTHER";
        };
        List<String> terms = new ArrayList<>();
        String geo = null;
        for (String t : header.subList(1, header.size())) {
            String term = t.strip();
            int p = term.lastIndexOf(": (");
            if (p > 0 && term.endsWith(")")) {
                geo = term.substring(p + 3, term.length() - 1);
                term = term.substring(0, p);
            }
            if (term.isEmpty() || term.length() > 200) throw ApiException.badRequest("A search term in the Trends file can't be read");
            terms.add(term);
        }
        Map<String, List<Double>> series = new LinkedHashMap<>();
        terms.forEach(t -> series.put(t, new ArrayList<>()));
        LocalDate first = null, last = null;
        for (int i = h + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> c = cells(lines[i]);
            LocalDate d = date(c.get(0).strip());
            if (d == null) throw ApiException.badRequest("Row " + (i + 1) + " of the Trends file doesn't start with a date");
            if (first == null) first = d;
            last = d;
            for (int k = 0; k < terms.size(); k++) {
                String v = k + 1 < c.size() ? c.get(k + 1).strip() : "";
                double x;
                if (v.equals("<1")) x = 0.5;
                else if (v.isEmpty()) x = 0;
                else {
                    try {
                        x = Double.parseDouble(v);
                    } catch (NumberFormatException e) {
                        throw ApiException.badRequest("Row " + (i + 1) + " of the Trends file has “" + v + "” where a number 0 to 100 should be");
                    }
                }
                if (x < 0 || x > 100) throw ApiException.badRequest("Trends values are 0 to 100; row " + (i + 1) + " has " + v);
                series.get(terms.get(k)).add(x);
            }
            if (series.get(terms.get(0)).size() > 2000) throw ApiException.badRequest("The Trends file is too long");
        }
        if (first == null) throw ApiException.badRequest("The Trends file has no rows");
        return new Trends(geo, period, first, last, terms, series);
    }

    /** The series for a keyword: the same words, ignoring case and spaces. */
    public static String matchTerm(String keyword, List<String> terms) {
        String k = String.join(" ", DocumentText.words(keyword));
        for (String t : terms) if (String.join(" ", DocumentText.words(t)).equals(k)) return t;
        return null;
    }

    static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else q = !q;
            } else if (ch == ',' && !q) {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    static LocalDate date(String s) {
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) return LocalDate.parse(s.substring(0, 10));
            if (s.matches("\\d{4}-\\d{2}")) return LocalDate.parse(s + "-01");
        } catch (DateTimeParseException e) {
            return null;
        }
        return null;
    }

    // ---- small helpers -----------------------------------------------------------------------------

    static List<String> significant(String s) {
        List<String> w = DocumentText.words(s).stream().filter(x -> x.codePointCount(0, x.length()) > 2 && !STOP.contains(x)).toList();
        return w.isEmpty() ? DocumentText.words(s) : w;
    }

    static final Set<String> STOP = Set.of("the", "and", "for", "with", "what", "how", "why", "are", "you", "your", "from", "into", "explained",
            "tutorial", "lecture", "class", "part", "video", "tamil", "english", "full", "easy", "best");

    static Instant parse(String s) {
        if (s == null) return null;
        try {
            return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException x) {
                return null;
            }
        }
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((s == null ? "" : s.strip()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
