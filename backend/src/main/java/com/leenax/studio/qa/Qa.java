package com.leenax.studio.qa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Academic QA's vocabulary (E5). Issues are Critical, Major or Minor; the critical kinds are
 * fixed by the specification, so the checker can't talk a wrong answer key down to minor.
 */
public final class Qa {

    private Qa() {
    }

    /** The report's categories, in report order. */
    public static final List<String> CATEGORIES = List.of("ACCURACY", "COVERAGE", "EXPLANATIONS", "REFERENCES", "MCQ_KEYS", "DIFFICULTY",
            "DUPLICATES");

    public static final Map<String, String> CATEGORY_LABEL = Map.of(
            "ACCURACY", "Accuracy", "COVERAGE", "Syllabus coverage", "EXPLANATIONS", "Explanations", "REFERENCES", "References",
            "MCQ_KEYS", "MCQ keys", "DIFFICULTY", "Difficulty", "DUPLICATES", "Duplicates");

    /** Every kind of issue, with the category it counts under. */
    public static final Map<String, String> TYPES;

    static {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("FACTUAL_ERROR", "ACCURACY");
        t.put("WRONG_KEY", "MCQ_KEYS");
        t.put("AMBIGUOUS", "MCQ_KEYS");
        t.put("MISSING_TOPIC", "COVERAGE");
        t.put("CONTRADICTION", "ACCURACY");
        t.put("UNSUPPORTED_CLAIM", "ACCURACY");
        t.put("MATH_ERROR", "ACCURACY");
        t.put("CODE_ERROR", "ACCURACY");
        t.put("INCONSISTENT", "ACCURACY");
        t.put("WEAK_EXPLANATION", "EXPLANATIONS");
        t.put("WEAK_EXAMPLE", "EXPLANATIONS");
        t.put("BAD_REFERENCE", "REFERENCES");
        t.put("COVERAGE_GAP", "COVERAGE");
        t.put("WRONG_DIFFICULTY", "DIFFICULTY");
        t.put("DUPLICATE", "DUPLICATES");
        t.put("RULE_BREAK", "MCQ_KEYS");
        t.put("GRAMMAR", "EXPLANATIONS");
        t.put("OTHER", "ACCURACY");
        TYPES = java.util.Collections.unmodifiableMap(t);
    }

    /** Always critical (and only these are): section 6 of the specification. */
    public static final Set<String> CRITICAL_TYPES = Set.of("FACTUAL_ERROR", "WRONG_KEY", "AMBIGUOUS", "MISSING_TOPIC", "CONTRADICTION");

    public static final List<String> BASES = List.of("UPLOADED", "EXTERNAL", "AI", "ESTIMATE", "UNVERIFIED");

    public static final Map<String, String> BASIS_LABEL = Map.of(
            "UPLOADED", "Course document page", "EXTERNAL", "Outside link given by the AI (not opened)", "AI", "The checker's own explanation",
            "ESTIMATE", "Estimate", "UNVERIFIED", "Unverified");

    /** Severity for a type: critical kinds are always critical; others are major or minor, never critical. */
    public static String severity(String type, String asked) {
        if (CRITICAL_TYPES.contains(type)) return "CRITICAL";
        String s = asked == null ? "" : asked.strip().toUpperCase(Locale.ROOT);
        return s.equals("MINOR") ? "MINOR" : "MAJOR";
    }

    public static String type(String asked) {
        String t = asked == null ? "" : asked.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return TYPES.containsKey(t) ? t : "OTHER";
    }

    public static String basis(String asked) {
        String b = asked == null ? "" : asked.strip().toUpperCase(Locale.ROOT);
        return BASES.contains(b) ? b : "UNVERIFIED";
    }

    public static boolean open(String status) {
        return "OPEN".equals(status) || "CORRECTED".equals(status);
    }

    // ---- what a run finds --------------------------------------------------------------------------

    /** Where a finding rests: a document page (with the words checked against it) or a link. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Evidence(String basis, String label, UUID documentId, UUID versionId, Integer page, String quote, Boolean found,
                           String url, String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Confirmation(String model, boolean agrees, String note) {
    }

    /** One finding before it is stored. proposal is a machine-applicable correction, or null. */
    public record Finding(String itemRef, String itemLabel, String type, String severity, String original, String explanation,
                          String basis, List<Evidence> evidence, String suggestion, JsonNode proposal, List<Confirmation> confirmations) {

        public String category() {
            return TYPES.get(type);
        }

        public Finding withConfirmations(List<Confirmation> c) {
            return new Finding(itemRef, itemLabel, type, severity, original, explanation, basis, evidence, suggestion, proposal, c);
        }
    }

    /** A claim checked in notes, with its verdict and basis. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Claim(String itemRef, String claim, String verdict, String basis, List<Evidence> evidence) {
    }

    /** One question as QA saw it: the key, the checker's own answer and, if run, a second model's. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionCheck(String questionId, int number, Integer key, Integer answer, Integer secondAnswer, String verdict,
                                String difficulty, String suggestedDifficulty) {
    }

    public record Tally(int checked, int passed, int failed) {
        public Tally plus(Tally o) {
            return new Tally(checked + o.checked, passed + o.passed, failed + o.failed);
        }

        public static Tally of(int checked, int failed) {
            return new Tally(checked, Math.max(0, checked - failed), failed);
        }
    }

    /** What a run stores: counts per category, the claims and questions checked, and its limits. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunResult(Map<String, Tally> categories, List<Claim> claims, List<QuestionCheck> questions, List<String> limitations,
                            String summary) {
    }

    // ---- stored rows -------------------------------------------------------------------------------

    public record RunRow(UUID id, UUID resourceId, UUID versionId, int versionNo, String contentHash, String mode, String model,
                         String provider, String secondModel, String writerModel, boolean independent, JsonNode result, String createdBy,
                         Instant createdAt) {
    }

    public record CorrectionRow(UUID id, UUID issueId, JsonNode proposal, String status, UUID appliedVersionId, String note,
                                String decidedBy, Instant decidedAt, Instant createdAt) {
    }

    public record IssueRow(UUID id, int number, UUID resourceId, UUID runId, UUID foundInVersionId, int foundInVersionNo, String itemRef,
                           String itemLabel, String category, String type, String severity, String original, String explanation,
                           String basis, JsonNode evidence, String suggestion, JsonNode confirmations, String status, String statusNote,
                           JsonNode recheck, String decidedBy, Instant decidedAt, Instant createdAt, CorrectionRow correction) {

        public IssueRow withCorrection(CorrectionRow c) {
            return new IssueRow(id, number, resourceId, runId, foundInVersionId, foundInVersionNo, itemRef, itemLabel, category, type,
                    severity, original, explanation, basis, evidence, suggestion, confirmations, status, statusNote, recheck, decidedBy,
                    decidedAt, createdAt, c);
        }
    }
}
