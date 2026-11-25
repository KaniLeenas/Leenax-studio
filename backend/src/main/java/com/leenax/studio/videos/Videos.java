package com.leenax.studio.videos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.slides.Slides;

import java.util.List;
import java.util.UUID;

/**
 * Lesson and quiz videos (E8) as data. A video's plan is its scenes and their narration text,
 * made from approved content (a presentation; or an MCQ set). Narration is spoken once per
 * plan: it carries the hash of the script and voice it was made from, and is dropped when
 * either changes. The picture is drawn by the video templates from the scenes.
 */
public final class Videos {

    private Videos() {
    }

    public enum Kind { LESSON, QUIZ }

    /** Lesson scenes: INTRO, SLIDE, OUTRO. Quiz scenes: QUIZ_INTRO, QUESTION, SUMMARY, OUTRO. */
    public static final List<String> LESSON_SCENES = List.of("INTRO", "SLIDE", "OUTRO");
    public static final List<String> QUIZ_SCENES = List.of("QUIZ_INTRO", "QUESTION", "SUMMARY", "OUTRO");
    public static final List<String> ASPECTS = List.of("16:9", "9:16");
    public static final int MAX_SCENES = 80;
    public static final int MAX_NARRATION = 5000;

    /** profileId, or accountId + voiceId (+ model). label is how it's shown. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Voice(UUID profileId, UUID accountId, String voiceId, String model, Double speed, String label) {
    }

    /** countdown: seconds to think before the answer is shown. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quiz(Integer countdown) {
    }

    /** answer: index into options (0 to 3). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(String questionId, Integer number, Integer total, String stem, Learning.Code code, List<String> options, Integer answer,
                           String explanation) {
    }

    /**
     * narration: what is said while the scene shows. answerNarration: for a question, what is
     * said after the answer is revealed. keywords: words highlighted as they appear. sizes: a
     * slide's text sizes (points) as the presentation's fit check measured them.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scene(String id, String kind, String title, List<String> lines, Slides.Slide slide, Question question, List<String> keywords,
                        String narration, String answerNarration, java.util.Map<String, Double> sizes) {
    }

    /** One spoken part: where it is in the narration file. part is MAIN or ANSWER. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(String sceneId, String part, Double start, Double duration) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Narration(UUID mediaId, String scriptHash, List<Segment> segments, Integer characters, String voiceLabel, String madeAt) {
    }

    /**
     * sourceVersionId: the approved presentation (lesson) or MCQ set (quiz) it was made from.
     * seoVersionId: the approved SEO report whose title it uses, if any. captions: burned in (default on).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Plan(String kind, String topicId, String topicTitle, String level, UUID sourceVersionId, UUID seoVersionId, String title,
                       String language, String style, String aspect, Voice voice, Quiz quiz, List<Scene> scenes, Narration narration,
                       Boolean captions) {
    }

    /** A diagram to look up by its source (drawn by the browser and kept, as in notes and slides). */
    public record FigureRef(String kind, String source) {
    }

    public static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }
}
