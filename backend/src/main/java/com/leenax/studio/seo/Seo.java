package com.leenax.studio.seo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * YouTube SEO's vocabulary (E6). Every number is a {@link Metric}: it names where it came
 * from (YouTube's API, a Trends download, a count Leenax made) or is marked estimated with
 * how it was worked out. Search volumes don't exist in any API, so none are shown.
 */
public final class Seo {

    private Seo() {
    }

    public static final String YT_SEARCH = "YouTube Data API search.list";
    public static final String YT_VIDEOS = "YouTube Data API videos.list (public numbers)";
    public static final String COUNTED = "Counted by Leenax";
    public static final String SETTING = "Leenax setting";

    /** Keyword kinds from the specification (Module 13). */
    public static final List<String> KEYWORD_KINDS = List.of("PRIMARY", "SECONDARY", "LONG_TAIL", "QUESTION", "BEGINNER", "EXAM", "PRACTICAL",
            "REGIONAL");

    /** Title styles (Module 15). */
    public static final List<String> TITLE_STYLES = List.of("EDUCATIONAL", "TUTORIAL", "BEGINNER", "EXAM", "PRACTICAL", "QUESTION", "REVISION",
            "MCQ");

    /** What a title is judged on (Module 15), each 1 to 5 by the AI, labelled estimated. */
    public static final List<String> TITLE_CRITERIA = List.of("keywordRelevance", "searchIntent", "clarity", "accuracy", "readability",
            "audienceRelevance", "differentiation", "clickAppeal", "length");

    /** Separate plans (Module 16). */
    public static final List<String> STRATEGIES = List.of("LESSON", "MCQ", "EXAM", "REVISION", "SHORTS");

    public static final int TITLE_MAX = 100;        // YouTube refuses longer titles
    public static final int TITLE_SHOWN = 70;       // about what search results show before cutting
    public static final int DESCRIPTION_MAX = 5000;
    public static final int TAGS_MAX_CHARS = 500;
    public static final int HASHTAGS_MAX = 15;      // YouTube ignores all hashtags past 60; 15 is its guidance

    /**
     * A number with its provenance. Either {@code source} says where it came from (with
     * {@code retrievedAt} for outside data), or {@code estimated} is true and {@code method}
     * says how. {@code inputs} holds the numbers a score was made from.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metric(Double value, String unit, String source, String retrievedAt, Boolean estimated, String method, String note,
                         Map<String, Metric> inputs) {

        public static Metric of(double value, String unit, String source, String retrievedAt) {
            return new Metric(value, unit, source, retrievedAt, null, null, null, null);
        }

        public static Metric counted(double value, String unit) {
            return new Metric(value, unit, COUNTED, null, null, null, null, null);
        }

        public static Metric estimate(double value, String unit, String method) {
            return new Metric(value, unit, null, null, true, method, null, null);
        }

        /** A number that isn't public (hidden likes, for example): no value, and why. */
        public static Metric unavailable(String unit, String why) {
            return new Metric(null, unit, null, null, null, null, why, null);
        }

        public Metric withInputs(Map<String, Metric> in) {
            return new Metric(value, unit, source, retrievedAt, estimated, method, note, in);
        }

        public Metric withNote(String n) {
            return new Metric(value, unit, source, retrievedAt, estimated, method, n, inputs);
        }
    }

    // ---- research ----------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Idea(String text, String language, String kind, String intent, String audience, String source) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Keyword(String text, String language, String kind, String intent, String audience, String source, boolean searched,
                          Metric relevance, Metric demand, Metric competition, Metric gap, Metric score, Boolean partial, String trend) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Search(String query, String language, String region, String retrievedAt, boolean cached, List<String> videoIds,
                         Metric results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Competitor(String videoId, String url, String title, String channel, String publishedAt, Metric duration, Metric views,
                             Metric likes, Metric comments, String description, List<String> tags, String thumbnail,
                             List<String> covers, List<String> foundBy, Integer rank) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Coverage(String subtopic, Metric competitorsCovering) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Analysis(String titleStrategies, List<String> contentGaps, List<String> repeatedQuestions, List<String> missingExplanations,
                           List<String> languageOpportunities, List<String> differentiation, String source) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrendUse(UUID importId, String filename, String term, String geo, String period, String lastDate, Metric recent,
                           String direction) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String name, String detail, String retrievedAt) {
    }

    /** Everything one research run found (stored as seo_research.data, never changed). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Research(String topicId, String topicTitle, List<String> subtopics, List<String> languages, String region, String startedAt,
                           List<Idea> ideas, List<Keyword> keywords, List<Search> searches, List<Competitor> competitors,
                           List<Coverage> coverage, Analysis analysis, List<TrendUse> trends, List<Source> sources,
                           Map<String, Metric> budget, Map<String, Metric> weights, List<String> limitations, String model) {
    }

    // ---- the SEO report (the approvable content) ---------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TitleCheck(String textHash, Metric length, Boolean hasKeyword, List<String> warnings, Map<String, Metric> ai,
                             Metric overall, String reasoning) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Title(String id, String text, String style, String language, TitleCheck check) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Chapter(String title, Metric start) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Suggestion(String title, String why) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Strategy(String titleIdea, String angle, String format, List<String> keywords, String notes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Report(String topicId, String topicTitle, UUID researchId, List<Title> titles, String recommendedTitleId,
                         String recommendationReason, String chosenTitleId, String description, List<String> primaryKeywords,
                         List<String> secondaryKeywords, List<String> tags, List<String> hashtags, String thumbnailText,
                         String thumbnailPrompt, List<Chapter> chapters, List<Suggestion> playlists, List<Suggestion> nextVideos,
                         Map<String, Strategy> strategies, String model) {
    }

    public static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }

    public static JsonNode nullSafe(JsonNode n) {
        return n == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : n;
    }
}
