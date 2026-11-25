package com.leenax.studio.syllabus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.common.ApiException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * The shapes of a syllabus and a topic list, as stored in a version's content, and the
 * shared rules for cleaning them. Both are trees of at most three levels: chapter,
 * topic, subtopic.
 */
public final class Content {

    private Content() {
    }

    public static final int MAX_DEPTH = 3;
    public static final int MAX_ITEMS = 1500;
    static final int TITLE_MAX = 300;
    static final int TEXT_MAX = 2000;
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Where an item comes from: a page of an uploaded document. found: the quoted words were found on that page. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(UUID documentId, UUID versionId, Integer page, String quote, Boolean found) {
    }

    // ---- syllabus ----------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(String id, String title, List<Source> sources, List<Node> children) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Outcome(String id, String code, String text, List<Source> sources) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Assessment(String id, String title, String kind, Double weight, String details, List<Source> sources) {
    }

    /** Where two documents disagree. It needs a resolution before the syllabus can be approved. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Contradiction(String id, String description, List<Source> sources, String resolution) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentRef(UUID documentId, UUID versionId, String title, Integer versionNo, String label) {
    }

    /** How the AI analysis went: which model, and how many of its citations held up. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Analysis(String model, String generatedAt, Integer citations, Integer citationsFound, Integer citationsRemoved) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Syllabus(String summary, List<Node> structure, List<Outcome> outcomes, List<Assessment> assessments,
                           List<Contradiction> contradictions, List<DocumentRef> documents, Analysis analysis) {
    }

    public static final Set<String> ASSESSMENT_KINDS = Set.of("EXAM", "COURSEWORK", "QUIZ", "PRACTICAL", "PRESENTATION", "PROJECT", "OTHER");

    // ---- topics ------------------------------------------------------------------------------------

    /**
     * One topic. syllabusItemIds: the syllabus items it covers (several after a merge, none
     * when added by hand). notInSyllabus: its syllabus item is gone from the approved syllabus.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Topic(String id, String title, List<String> syllabusItemIds, Boolean selected, String difficulty,
                        List<String> prerequisites, List<Source> sources, Boolean notInSyllabus, List<Topic> children) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Topics(UUID syllabusVersionId, List<Topic> nodes) {
    }

    public static final List<String> DIFFICULTIES = List.of("INTRODUCTORY", "INTERMEDIATE", "ADVANCED");

    // ---- shared cleaning ---------------------------------------------------------------------------

    static <T> T read(JsonNode raw, Class<T> type, String what) {
        if (raw == null || !raw.isObject()) throw ApiException.badRequest("The " + what + " must be a JSON object");
        try {
            return JSON.treeToValue(raw, type);
        } catch (Exception e) {
            throw ApiException.badRequest("The " + what + " couldn't be read: " + firstLine(e.getMessage()));
        }
    }

    static JsonNode tree(Object value) {
        return JSON.valueToTree(value);
    }

    /** Trimmed, NFC, single spaces; null when blank. Longer than max is an error, not a silent cut. */
    static String text(String s, int max, String field) {
        if (s == null) return null;
        String t = Normalizer.normalize(s, Normalizer.Form.NFC).replaceAll("[\\u200B\\uFEFF]", "")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw ApiException.badRequest(field + " is longer than " + max + " characters");
        return t;
    }

    static String required(String s, int max, String field) {
        String t = text(s, max, field);
        if (t == null) throw ApiException.badRequest(field + " can't be empty");
        return t;
    }

    /** A fresh id when missing; an error when malformed or repeated. */
    static String id(String id, Set<String> seen) {
        String v = id == null || id.isBlank() ? newId() : id.strip();
        if (!ID.matcher(v).matches()) throw ApiException.badRequest("Item id “" + v + "” isn't valid");
        if (!seen.add(v)) throw ApiException.badRequest("Item id “" + v + "” is used twice");
        return v;
    }

    static String newId() {
        return "i" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFL);
    }

    static List<Source> sources(List<Source> in) {
        List<Source> out = new ArrayList<>();
        if (in == null) return out;
        Set<String> seen = new HashSet<>();
        for (Source s : in) {
            if (s == null || s.documentId() == null || s.versionId() == null || s.page() == null) continue;
            if (s.page() < 1) throw ApiException.badRequest("A source page must be 1 or more");
            String quote = text(s.quote(), 500, "A quoted source");
            if (seen.add(s.versionId() + ":" + s.page() + ":" + quote)) {
                out.add(new Source(s.documentId(), s.versionId(), s.page(), quote, s.found()));
            }
            if (out.size() > 20) throw ApiException.badRequest("An item can have at most 20 sources");
        }
        return out;
    }

    static <T> List<T> list(List<T> in) {
        return in == null ? List.of() : in;
    }

    static String firstLine(String s) {
        if (s == null) return "invalid";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
