package com.leenax.studio.seo;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.documents.DocumentText;
import com.leenax.studio.seo.Seo.Analysis;
import com.leenax.studio.seo.Seo.Chapter;
import com.leenax.studio.seo.Seo.Competitor;
import com.leenax.studio.seo.Seo.Idea;
import com.leenax.studio.seo.Seo.Keyword;
import com.leenax.studio.seo.Seo.Metric;
import com.leenax.studio.seo.Seo.Strategy;
import com.leenax.studio.seo.Seo.Suggestion;
import com.leenax.studio.seo.Seo.Title;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Prompts for keyword ideas and the SEO plan, and how the replies are read. The AI suggests
 * words and writes titles and plans; it never supplies a demand, view or click figure, and
 * its 1-to-5 title scores are labelled as its own estimates.
 */
public final class SeoAi {

    private SeoAi() {
    }

    static final Map<String, String> LANGUAGE = Map.of("en", "English", "ta", "Tamil (in Tamil script; add a Tanglish variant only if students search that way)");

    static final String NO_FIGURES = """
            Never state search volumes, view counts, click-through rates, rankings or any other figure about YouTube performance: \
            you don't have them, and Leenax measures what it can from YouTube's API itself.""";

    // ---- keyword ideas -----------------------------------------------------------------------------

    public static String ideasSystem() {
        return """
                You suggest YouTube search keywords for a university lesson video. Suggest what students would really type, in each \
                language asked, covering these kinds: PRIMARY, SECONDARY, LONG_TAIL, QUESTION, BEGINNER, EXAM, PRACTICAL, REGIONAL \
                (for example with the country, university or exam board, if the topic suggests one).
                %s
                For each: "text", "language" (en or ta), "kind", "intent" (LEARN, EXAM_PREP, PROBLEM_SOLVING, REVISION or \
                REFERENCE), "audience" (one short phrase).
                Reply with JSON only: {"keywords": [{"text": "...", "language": "en", "kind": "PRIMARY", "intent": "LEARN", \
                "audience": "first-year students"}]}. 20 to 40 keywords, most useful first. Put the 3 best PRIMARY ones per language first.
                """.formatted(NO_FIGURES);
    }

    public static String ideasPrompt(String topic, String path, List<String> subtopics, List<String> headings, List<String> languages,
                                     String subject, String guidance) {
        StringBuilder sb = new StringBuilder();
        if (subject != null) sb.append("Subject: ").append(subject).append('\n');
        sb.append("Topic: ").append(topic).append('\n');
        if (path != null) sb.append("Where it sits: ").append(path).append('\n');
        if (!subtopics.isEmpty()) sb.append("Subtopics: ").append(String.join("; ", subtopics)).append('\n');
        if (!headings.isEmpty()) sb.append("Headings of the approved notes: ").append(String.join("; ", headings.stream().limit(40).toList())).append('\n');
        sb.append("Languages: ").append(String.join(", ", languages.stream().map(l -> LANGUAGE.getOrDefault(l, l)).toList())).append('\n');
        if (guidance != null && !guidance.isBlank()) sb.append("Guidance: ").append(guidance).append('\n');
        return sb.toString();
    }

    public static List<Idea> readIdeas(JsonNode reply, List<String> languages, String model) {
        List<Idea> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode k : reply.path("keywords")) {
            String text = k.path("text").asText("").strip().replaceAll("\\s+", " ");
            if (text.isEmpty() || text.length() > 150) continue;
            String lang = k.path("language").asText("").strip().toLowerCase(Locale.ROOT);
            if (lang.matches("[a-z]{2}") ? !languages.contains(lang) : !languages.contains(lang = guessLanguage(text))) continue;
            String key = String.join(" ", DocumentText.words(text));
            if (key.isEmpty() || !seen.add(lang + "|" + key)) continue;
            String kind = k.path("kind").asText("").toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (!Seo.KEYWORD_KINDS.contains(kind)) kind = "SECONDARY";
            out.add(new Idea(text, lang, kind, clip(k.path("intent").asText(null), 30), clip(k.path("audience").asText(null), 120),
                    "AI suggestion (" + model + ")"));
            if (out.size() >= 40) break;
        }
        return out;
    }

    /** Tamil script means Tamil; anything else is treated as English. */
    static String guessLanguage(String s) {
        return s.codePoints().anyMatch(c -> c >= 0x0B80 && c <= 0x0BFF) ? "ta" : "en";
    }

    // ---- the plan: analysis, titles, description, tags, chapters, strategies -----------------------

    public static String planSystem() {
        return """
                You plan the YouTube SEO for one university lesson video and the videos around it. Be accurate and specific to the \
                topic; no clickbait, no misleading promises, no claims the video can't keep.
                %s
                Write:
                - "analysis": "titleStrategies" (how the competitor titles below are written), "contentGaps", "repeatedQuestions", \
                "missingExplanations", "languageOpportunities", "differentiation" (short lists from the competitor titles and \
                descriptions given; say what you can't tell from titles and descriptions alone).
                - "titles": 12 to 15, across these styles: EDUCATIONAL, TUTORIAL, BEGINNER, EXAM, PRACTICAL, QUESTION, REVISION, MCQ; \
                each in a language asked. At most 70 characters where possible, never over 100. For each, "scores" 1 to 5 on \
                keywordRelevance, searchIntent, clarity, accuracy, readability, audienceRelevance, differentiation, clickAppeal, length; \
                and one sentence "reasoning".
                - "recommended": the index (0-based) of the strongest title and "recommendedReason" (transparent: which criteria decided).
                - "description": 150 to 300 words for the lesson video, the main keywords early, no invented links.
                - "tags": 10 to 20, "hashtags": 3 to 5 (with #), "thumbnailText" (at most 5 words), "thumbnailPrompt" (a picture \
                description for an image generator: no text, no real people, no logos).
                - "chapters": from the notes headings, each with "title" and a planned "startSeconds".
                - "playlists" and "nextVideos": each with "title" and "why".
                - "strategies": LESSON, MCQ, EXAM, REVISION and SHORTS, each with "titleIdea", "angle", "format" (length and \
                shape, in words), "keywords" (from the list given), "notes".
                Reply with JSON only, these keys at the top level.
                """.formatted(NO_FIGURES);
    }

    public static String planPrompt(String topic, List<String> subtopics, List<String> headings, int questions, List<Keyword> keywords,
                                    List<Competitor> competitors, List<String> gaps, List<String> languages, String guidance) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(topic).append('\n');
        if (!subtopics.isEmpty()) sb.append("Subtopics: ").append(String.join("; ", subtopics)).append('\n');
        if (!headings.isEmpty()) sb.append("Notes headings: ").append(String.join("; ", headings.stream().limit(40).toList())).append('\n');
        if (questions > 0) sb.append("Approved multiple-choice questions on this topic: ").append(questions).append('\n');
        sb.append("Languages: ").append(String.join(", ", languages.stream().map(l -> LANGUAGE.getOrDefault(l, l)).toList())).append('\n');
        sb.append("\nKeywords, best first:\n");
        keywords.stream().limit(25).forEach(k -> sb.append("- ").append(k.text()).append(" [").append(k.language()).append(", ")
                .append(k.kind().toLowerCase(Locale.ROOT)).append("]\n"));
        if (!gaps.isEmpty()) sb.append("\nSubtopics few top videos cover: ").append(String.join("; ", gaps)).append('\n');
        if (competitors.isEmpty()) {
            sb.append("\nNo competitor videos were looked up for this run.\n");
        } else {
            sb.append("\nTop competitor videos found on YouTube (titles and the start of descriptions; numbers are left out on purpose):\n");
            for (Competitor c : competitors.stream().limit(20).toList()) {
                String d = c.description() == null ? "" : c.description().replaceAll("\\s+", " ");
                sb.append("- “").append(c.title()).append("” by ").append(c.channel()).append(": ").append(d.length() > 200 ? d.substring(0, 200) + "…" : d).append('\n');
            }
        }
        if (guidance != null && !guidance.isBlank()) sb.append("\nGuidance: ").append(guidance).append('\n');
        return sb.toString();
    }

    public record Plan(Analysis analysis, List<Title> titles, String recommendedId, String recommendedReason, String description,
                       List<String> tags, List<String> hashtags, String thumbnailText, String thumbnailPrompt, List<Chapter> chapters,
                       List<Suggestion> playlists, List<Suggestion> nextVideos, Map<String, Strategy> strategies) {
    }

    public static Plan readPlan(JsonNode r, List<String> primary, List<String> languages, String model) {
        JsonNode a = r.path("analysis");
        Analysis analysis = new Analysis(clip(a.path("titleStrategies").asText(null), 2000), strings(a.path("contentGaps"), 10, 400),
                strings(a.path("repeatedQuestions"), 10, 400), strings(a.path("missingExplanations"), 10, 400),
                strings(a.path("languageOpportunities"), 10, 400), strings(a.path("differentiation"), 10, 400), "AI analysis of public titles and descriptions (" + model + ")");
        String judged = "AI judgement, 1 to 5 (" + model + ")";
        List<Title> titles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int recommendedIndex = r.path("recommended").asInt(-1);
        String recommendedId = null;
        JsonNode list = r.path("titles");
        for (int i = 0; i < list.size() && titles.size() < 15; i++) {
            JsonNode t = list.get(i);
            String text = t.path("text").asText("").strip().replaceAll("\\s+", " ");
            if (text.isEmpty() || !seen.add(text.toLowerCase(Locale.ROOT))) continue;
            String style = t.path("style").asText("").toUpperCase(Locale.ROOT);
            if (!Seo.TITLE_STYLES.contains(style)) style = "EDUCATIONAL";
            String lang = t.path("language").asText("").toLowerCase(Locale.ROOT);
            if (!languages.contains(lang)) lang = guessLanguage(text);
            Map<String, Metric> scores = new LinkedHashMap<>();
            double sum = 0;
            int n = 0;
            for (String c : Seo.TITLE_CRITERIA) {
                JsonNode v = t.path("scores").path(c);
                if (v.isNumber() && v.asDouble() >= 1 && v.asDouble() <= 5) {
                    scores.put(c, Metric.estimate(v.asDouble(), "1-5", judged));
                    sum += v.asDouble();
                    n++;
                }
            }
            Metric overall = n == 0 ? null : Metric.estimate(SeoLogic.round(sum / n), "1-5", "Mean of the AI's " + n + " scores");
            String id = "t" + (titles.size() + 1);
            titles.add(new Title(id, text, style, lang, SeoLogic.checkTitle(text, primary, scores.isEmpty() ? null : scores, overall,
                    clip(t.path("reasoning").asText(null), 500))));
            if (i == recommendedIndex) recommendedId = id;
        }
        // the recommendation must be a usable title; otherwise the best usable one by the AI's mean
        Title rec = null;
        for (Title t : titles) if (t.id().equals(recommendedId)) rec = t;
        String reason = clip(r.path("recommendedReason").asText(null), 1000);
        if (rec == null || !SeoLogic.usable(rec.check())) {
            rec = titles.stream().filter(t -> SeoLogic.usable(t.check()))
                    .max((x, y) -> Double.compare(val(x.check().overall()), val(y.check().overall()))).orElse(null);
            reason = rec == null ? null : "Chosen by Leenax: the usable title with the highest mean AI score (the AI's own pick wasn't usable).";
        }
        List<Chapter> chapters = new ArrayList<>();
        for (JsonNode c : r.path("chapters")) {
            String title = clip(c.path("title").asText(""), 100);
            if (title == null || title.isBlank()) continue;
            int s = Math.max(0, c.path("startSeconds").asInt(0));
            chapters.add(new Chapter(title, Metric.estimate(chapters.isEmpty() ? 0 : s, "seconds",
                    "Planned start; set the real time after recording (YouTube needs the first chapter at 0:00)")));
            if (chapters.size() >= 30) break;
        }
        Map<String, Strategy> strategies = new LinkedHashMap<>();
        for (String k : Seo.STRATEGIES) {
            JsonNode s = r.path("strategies").path(k);
            if (!s.isObject()) continue;
            strategies.put(k, new Strategy(clip(s.path("titleIdea").asText(null), 200), clip(s.path("angle").asText(null), 1000),
                    clip(s.path("format").asText(null), 300), strings(s.path("keywords"), 10, 150), clip(s.path("notes").asText(null), 1500)));
        }
        return new Plan(analysis, titles, rec == null ? null : rec.id(), rec == null ? null : reason,
                clip(r.path("description").asText(null), Seo.DESCRIPTION_MAX), tags(strings(r.path("tags"), 30, 100)),
                hashtags(strings(r.path("hashtags"), 15, 60)), clip(r.path("thumbnailText").asText(null), 60),
                clip(r.path("thumbnailPrompt").asText(null), 1000), chapters, suggestions(r.path("playlists")), suggestions(r.path("nextVideos")),
                strategies);
    }

    static double val(Metric m) {
        return m == null || m.value() == null ? 0 : m.value();
    }

    /** Tags within YouTube's 500 characters in total (each counted with its comma). */
    public static List<String> tags(List<String> in) {
        List<String> out = new ArrayList<>();
        int total = 0;
        Set<String> seen = new HashSet<>();
        for (String t : in) {
            String x = t.replace("#", "").replace(",", " ").strip().replaceAll("\\s+", " ");
            if (x.isEmpty() || !seen.add(x.toLowerCase(Locale.ROOT))) continue;
            int add = x.length() + (x.contains(" ") ? 2 : 0) + (out.isEmpty() ? 0 : 1);
            if (total + add > Seo.TAGS_MAX_CHARS) break;
            total += add;
            out.add(x);
        }
        return out;
    }

    public static List<String> hashtags(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String h : in) {
            String x = h.strip().replaceAll("\\s+", "");
            if (x.isEmpty()) continue;
            if (!x.startsWith("#")) x = "#" + x;
            if (x.length() > 1 && !out.contains(x)) out.add(x);
            if (out.size() >= Seo.HASHTAGS_MAX) break;
        }
        return out;
    }

    static List<Suggestion> suggestions(JsonNode arr) {
        List<Suggestion> out = new ArrayList<>();
        for (JsonNode s : arr) {
            String t = clip(s.path("title").asText(""), 200);
            if (t != null && !t.isBlank()) out.add(new Suggestion(t, clip(s.path("why").asText(null), 500)));
            if (out.size() >= 8) break;
        }
        return out;
    }

    static List<String> strings(JsonNode arr, int max, int each) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : arr) {
            String t = clip(s.asText(""), each);
            if (t != null && !t.isBlank()) out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
