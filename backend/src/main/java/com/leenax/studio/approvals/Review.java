package com.leenax.studio.approvals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** The approval engine's vocabulary (E3). */
public final class Review {

    private Review() {
    }

    /** What can be approved. More kinds (notes, MCQs, ...) come in later increments. */
    public enum Kind {
        SYLLABUS("Syllabus"), TOPICS("Topics"), TOPIC_ANALYSIS("Topic analysis"), NOTES("Notes"), MCQ("Question bank"), QA("QA report"), SEO("SEO report"), SLIDES("Presentation"), LESSON_VIDEO("Lesson video"), QUIZ_VIDEO("Quiz video");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Review status of one version. */
    public enum Status { DRAFT, IN_REVIEW, CHANGES_REQUESTED, APPROVED, REJECTED, OUTDATED, ARCHIVED }

    public enum Origin { AI, MANUAL, EDIT, REVIEWER }

    /** A reviewer's decision on the version under review. */
    public enum Decision {
        APPROVE(Status.APPROVED, "APPROVED"),
        APPROVE_WITH_EDITS(Status.APPROVED, "APPROVED_WITH_EDITS"),
        REQUEST_CHANGES(Status.CHANGES_REQUESTED, "CHANGES_REQUESTED"),
        REJECT(Status.REJECTED, "REJECTED");

        private final Status result;
        private final String action;

        Decision(Status result, String action) {
            this.result = result;
            this.action = action;
        }

        public Status result() {
            return result;
        }

        public String action() {
            return action;
        }

        /** Sending work back needs a reason the author can act on. */
        public boolean needsComment() {
            return this == REQUEST_CHANGES || this == REJECT;
        }
    }

    /**
     * One check on a version's content. Blocking checks must pass before it can be
     * submitted or approved; the others are warnings shown to the reviewer.
     */
    public record Check(String code, boolean ok, boolean blocking, String message) {

        public static Check pass(String code, String message) {
            return new Check(code, true, false, message);
        }

        public static Check fail(String code, String message) {
            return new Check(code, false, true, message);
        }

        public static Check warn(String code, String message) {
            return new Check(code, false, false, message);
        }
    }

    public static boolean passes(List<Check> checks) {
        return checks.stream().noneMatch(c -> c.blocking() && !c.ok());
    }

    /**
     * What a content handler may ask about the project while checking.
     * approvedVersionOf: the approved version of the project's syllabus or topic list.
     * approvedContentOf: its content. approvedOfKind: approved version ids of every notes
     * or bank resource. others: the newest content of the other resources of the same kind.
     */
    public record Context(UUID projectId, UUID resourceId, Function<Kind, UUID> approvedVersionOf,
                          Function<Kind, JsonNode> approvedContentOf, Function<Kind, List<UUID>> approvedOfKind,
                          java.util.function.Supplier<List<JsonNode>> others) {

        /** For handlers that only need the approved syllabus or topics (and tests). */
        public static Context simple(UUID projectId, Function<Kind, UUID> approvedVersionOf) {
            return new Context(projectId, null, approvedVersionOf, k -> null, k -> List.of(), List::of);
        }
    }

    /** Per-kind content rules: cleaning and validation, and the checks before approval. */
    public interface ContentHandler {
        Kind kind();

        /**
         * Validates and cleans submitted content; throws ApiException (400) when it can't be used.
         * {@code dependsOn} is the version this one was built from, if any (a topic list's syllabus).
         */
        JsonNode normalise(JsonNode raw, UUID dependsOn);

        List<Check> checks(JsonNode content, Context ctx);

        /** A short list of what changed between two versions, for the decision record. */
        List<String> diff(JsonNode before, JsonNode after);

        /** False for content Leenax makes itself (QA reports): nobody edits it, it's made again. */
        default boolean editableByHand() {
            return true;
        }
    }

    /**
     * Checks that depend on more than a version's content, such as open QA issues (E5).
     * They are added to every version's checks; a failing blocking one stops submit and approval.
     */
    public interface Gate {
        List<Check> checks(UUID projectId, UUID resourceId, Kind kind, String contentHash);
    }

    // ---- canonical JSON and hashing ----------------------------------------------------------------

    /** Sorted keys, so the same content always hashes the same. */
    public static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    public static String canonical(JsonNode content) {
        try {
            Object plain = CANONICAL.treeToValue(content, Object.class);
            return CANONICAL.writeValueAsString(plain);
        } catch (Exception e) {
            throw new IllegalArgumentException("Content can't be written as JSON", e);
        }
    }

    public static String hash(String canonicalJson) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
