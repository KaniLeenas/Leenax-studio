package com.leenax.studio.notes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/** Shapes of notes and question banks as stored in a version's content (E4). */
public final class Learning {

    private Learning() {
    }

    /** The five formats of notes. */
    public static final List<String> LEVELS = List.of("BEGINNER", "INTERMEDIATE", "ADVANCED", "UNIVERSITY", "EXAM");

    /** The eight kinds of question. */
    public static final List<String> STYLES = List.of("BEGINNER", "INTERMEDIATE", "ADVANCED", "APPLICATION", "SCENARIO", "CODE",
            "CALCULATION", "EXAM");

    public static final List<String> DIFFICULTIES = List.of("BEGINNER", "INTERMEDIATE", "ADVANCED");

    /** Set by the academic QA in E5; until then every question is unverified. */
    public static final List<String> VERIFICATION = List.of("UNVERIFIED", "PASSED", "FAILED");

    public static String levelLabel(String level) {
        return switch (level) {
            case "BEGINNER" -> "Beginner";
            case "INTERMEDIATE" -> "Intermediate";
            case "ADVANCED" -> "Advanced";
            case "UNIVERSITY" -> "University";
            case "EXAM" -> "Exam-focused";
            default -> level;
        };
    }

    /** A page or note section something came from. found: the quoted words are on that page. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reference(String label, UUID documentId, UUID versionId, Integer page, String note, Boolean found) {
    }

    /** Notes on one topic at one level. doc is the editor document (see NoteDoc). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Notes(String topicId, String topicTitle, String level, UUID topicsVersionId, JsonNode doc,
                        List<Reference> references, String model) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Code(String language, String text) {
    }

    /**
     * One multiple-choice question: four options, one key (answer: 0 to 3), an explanation,
     * and optionally why each wrong option is wrong.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Question(String id, String style, String difficulty, String stem, Code code, List<String> options, Integer answer,
                           String explanation, List<String> optionExplanations, String subtopic, Reference source,
                           String verification) {
    }

    /** A topic's question bank, written from one approved version of its notes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Bank(String topicId, String topicTitle, UUID notesVersionId, String notesLevel, Boolean allowAllNone,
                       List<Question> questions) {
    }
}
