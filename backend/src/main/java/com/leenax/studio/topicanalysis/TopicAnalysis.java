package com.leenax.studio.topicanalysis;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A topic analysis (E11): what one topic must teach and how its questions and lesson video are
 * planned. It is approved before any questions or video are made from it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopicAnalysis(
        String topicId,
        String topicTitle,
        String topicPath,
        UUID topicsVersionId,
        String summary,
        Difficulty difficulty,
        List<Item> prerequisites,
        List<Objective> objectives,
        List<Concept> concepts,
        List<Misconception> misconceptions,
        List<Example> examples,
        List<Item> examRelevance,
        Integer mcqCount,
        List<McqPlan> mcqPlan,
        Integer videoMinutes,
        VideoPlan videoPlan,
        List<Note> gaps,
        List<Note> questions,
        Made analysis) {

    public static final Set<String> BLOOM = Set.of("REMEMBER", "UNDERSTAND", "APPLY", "ANALYSE", "EVALUATE", "CREATE");
    public static final Set<String> BASIS = Set.of("DOCUMENT", "GENERAL");
    public static final Set<String> EXAMPLE_KINDS = Set.of("WORKED", "CODE", "CALCULATION", "CASE");
    public static final Set<String> SHOWS = Set.of("CONCEPT", "DEFINITION", "DIAGRAM", "EXAMPLE", "COMPARISON", "APPLICATIONS", "SUMMARY");

    /** Where a statement comes from: a page of a course document, and words copied from it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(UUID documentId, UUID versionId, String title, Integer page, String quote, Boolean found) {
    }

    public record Difficulty(String level, String why) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String text, String basis, List<Source> sources) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Objective(String id, String text, String bloom, List<String> outcomes, List<Source> sources) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Concept(String id, String term, String definition, String basis, List<Source> sources) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Misconception(Integer number, String wrong, String right, String concept, String basis) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Example(String title, String kind, List<String> concepts, List<Source> sources) {
    }

    /** How many questions an objective gets, by difficulty, and which question styles fit it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record McqPlan(String objective, Integer count, Map<String, Integer> difficulties, List<String> styles) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VideoPlan(String hook, List<Scene> scenes) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scene(String title, String goal, String shows, Integer seconds, List<String> concepts) {
    }

    public record Note(String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Made(String model, String madeAt, Integer quotesKept, Integer quotesFound, Integer quotesRemoved) {
    }
}
