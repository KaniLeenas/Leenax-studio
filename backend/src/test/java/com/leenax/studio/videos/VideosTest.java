package com.leenax.studio.videos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.editor.RenderSettings;
import com.leenax.studio.editor.RenderValidator;
import com.leenax.studio.editor.TimelineDocument;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.slides.Slides;
import com.leenax.studio.videos.Videos.Narration;
import com.leenax.studio.videos.Videos.Plan;
import com.leenax.studio.videos.Videos.Scene;
import com.leenax.studio.videos.Videos.Segment;
import com.leenax.studio.videos.Videos.Voice;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideosTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Voice VOICE = new Voice(null, UUID.randomUUID(), "ta-IN-Wavenet-A", null, null, "Wavenet A (Tamil)");

    static Slides.Deck deck() {
        return new Slides.Deck("t5", "1NF to 3NF", "BEGINNER", null, null, null, "DARK_TECH", "Normalisation", List.of(
                new Slides.Slide("a", "TITLE", "TITLE", "Normalisation", "Database Systems", null, null, null, null, null, null, null, null, null, null),
                new Slides.Slide("b", "OBJECTIVES", "BULLETS", "You will", null, List.of("Explain update anomalies", "Reach third normal form"), null, null,
                        null, null, null, null, null, "Start with the spreadsheet story.", null),
                new Slides.Slide("c", "DEFINITION", "DEFINITION", "Definition", null, null, "Transitive dependency", "A depends on B through C.", null,
                        null, null, null, null, null, null)), null, "m");
    }

    static Learning.Bank bank() {
        List<Learning.Question> qs = new ArrayList<>();
        qs.add(new Learning.Question("q1", "BEGINNER", "EASY", "Which form removes repeating groups?", null, List.of("1NF", "2NF", "3NF", "BCNF"), 0,
                "1NF requires atomic values.", null, null, null, null));
        qs.add(new Learning.Question("q2", "BEGINNER", "EASY", "What does 2NF remove", null, List.of("Joins", "Partial dependencies", "Keys", "Rows"), 1,
                null, null, null, null, null));
        qs.add(new Learning.Question("bad", "BEGINNER", "EASY", "Broken", null, List.of("only", "three", "options"), 0, null, null, null, null, null));
        return new Learning.Bank("t5", "1NF to 3NF", null, "BEGINNER", false, qs);
    }

    static Narration narrate(Plan p) {
        List<Segment> segs = new ArrayList<>();
        double t = 0;
        for (VideoPlans.Spoken s : VideoPlans.spoken(p)) {
            double d = 2 + s.text().length() / 20.0;
            segs.add(new Segment(s.sceneId(), s.part(), t, d));
            t += d + 0.4;
        }
        return new Narration(UUID.randomUUID(), VideoPlans.scriptHash(p), segs, VideoPlans.characters(p), "Wavenet A", Instant.now().toString());
    }

    static Plan with(Plan p, Narration n) {
        return new Plan(p.kind(), p.topicId(), p.topicTitle(), p.level(), p.sourceVersionId(), p.seoVersionId(), p.title(), p.language(), p.style(),
                p.aspect(), p.voice(), p.quiz(), p.scenes(), n, p.captions());
    }

    static boolean ok(List<Check> c, String code) {
        return c.stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow().ok();
    }

    // ---- plans -------------------------------------------------------------------------------------

    @Test
    void aLessonIsNarratedFromTheApprovedSpeakerNotes() {
        Plan p = VideoPlans.lesson(deck(), UUID.randomUUID(), null, "Normalisation in 10 minutes", List.of("third normal form", "xy"), "EN", "16:9", VOICE);
        assertEquals(List.of("INTRO", "SLIDE", "SLIDE", "OUTRO"), p.scenes().stream().map(Scene::kind).toList());
        assertEquals("Normalisation in 10 minutes", p.title());
        assertEquals("Start with the spreadsheet story.", p.scenes().get(1).narration());
        assertEquals("Definition. Transitive dependency: A depends on B through C.", p.scenes().get(2).narration(), "without notes, the slide is read as it is");
        assertEquals(List.of("third normal form"), p.scenes().get(1).keywords());
        assertEquals(List.of("Transitive dependency"), p.scenes().get(2).keywords());
        assertEquals(List.of("Database Systems"), p.scenes().get(0).lines());
        assertEquals("DARK_TECH", p.style());
    }

    @Test
    void aQuizReadsEachQuestionThenTheAnswerAndSkipsIncompleteOnes() {
        Plan p = VideoPlans.quiz(bank(), UUID.randomUUID(), UUID.randomUUID(), null, "EN", "9:16", VOICE, 7, 10);
        assertEquals(List.of("QUIZ_INTRO", "QUESTION", "QUESTION", "SUMMARY", "OUTRO"), p.scenes().stream().map(Scene::kind).toList());
        Scene q2 = p.scenes().get(2);
        assertEquals("Question 2. What does 2NF remove. A: Joins. B: Partial dependencies. C: Keys. D: Rows.", q2.narration());
        assertEquals("The answer is B: Partial dependencies.", q2.answerNarration());
        assertEquals("The answer is A: 1NF. 1NF requires atomic values.", p.scenes().get(1).answerNarration());
        assertTrue(p.scenes().get(0).narration().contains("2 questions, with 7 seconds for each"));
        assertEquals(List.of("1. A: 1NF", "2. B: Partial dependencies"), p.scenes().get(3).lines());
        assertEquals(7, p.quiz().countdown());
        Plan ta = VideoPlans.quiz(bank(), UUID.randomUUID(), UUID.randomUUID(), null, "TA", "9:16", VOICE, 5, 1);
        assertTrue(ta.scenes().get(1).narration().startsWith("கேள்வி 1."));
        assertEquals(1, ta.scenes().stream().filter(s -> s.kind().equals("QUESTION")).count());
    }

    // ---- rules -------------------------------------------------------------------------------------

    @Test
    void narrationIsKeptOnlyWhileTheWordsAndVoiceAreUnchanged() {
        VideoRules rules = new VideoRules.Lesson();
        Plan p = VideoPlans.lesson(deck(), UUID.randomUUID(), null, null, List.of(), "EN", "16:9", VOICE);
        p = VideoRules.read(rules.normalise(JSON.valueToTree(p), null));
        Plan narrated = VideoRules.read(rules.normalise(JSON.valueToTree(with(p, narrate(p))), null));
        assertNotNull(narrated.narration());
        List<Check> checks = rules.checks(JSON.valueToTree(narrated), Context.simple(UUID.randomUUID(), k -> null));
        assertTrue(checks.stream().noneMatch(c -> c.blocking() && !c.ok()), checks.toString());

        List<Scene> edited = new ArrayList<>(narrated.scenes());
        Scene s = edited.get(1);
        edited.set(1, new Scene(s.id(), s.kind(), s.title(), s.lines(), s.slide(), s.question(), s.keywords(), s.narration() + " Edited.", null, null));
        Plan changed = new Plan(p.kind(), p.topicId(), p.topicTitle(), p.level(), p.sourceVersionId(), p.seoVersionId(), p.title(), p.language(), p.style(),
                p.aspect(), p.voice(), p.quiz(), edited, narrated.narration(), p.captions());
        JsonNode out = rules.normalise(JSON.valueToTree(changed), null);
        assertNull(VideoRules.read(out).narration(), "edited words drop the narration");
        assertFalse(ok(rules.checks(out, Context.simple(UUID.randomUUID(), k -> null)), "narration"));
        assertTrue(rules.diff(JSON.valueToTree(narrated), out).contains("Narration changed on 1 scene"));

        Plan otherVoice = new Plan(p.kind(), p.topicId(), p.topicTitle(), p.level(), p.sourceVersionId(), p.seoVersionId(), p.title(), p.language(), p.style(),
                p.aspect(), new Voice(null, VOICE.accountId(), "ta-IN-Wavenet-B", null, null, "B"), p.quiz(), narrated.scenes(), narrated.narration(), p.captions());
        assertNull(VideoRules.read(rules.normalise(JSON.valueToTree(otherVoice), null)).narration(), "a new voice drops the narration");
    }

    @Test
    void badPlansAreRefused() {
        VideoRules quizRules = new VideoRules.QuizVideo();
        Plan p = VideoPlans.quiz(bank(), UUID.randomUUID(), UUID.randomUUID(), null, "EN", "9:16", VOICE, 5, 10);
        assertThrows(ApiException.class, () -> new VideoRules.Lesson().normalise(JSON.valueToTree(p), null), "a quiz isn't a lesson");
        Plan longCount = new Plan(p.kind(), p.topicId(), p.topicTitle(), p.level(), p.sourceVersionId(), p.seoVersionId(), p.title(), p.language(), p.style(),
                p.aspect(), p.voice(), new Videos.Quiz(60), p.scenes(), null, true);
        assertThrows(ApiException.class, () -> quizRules.normalise(JSON.valueToTree(longCount), null));
        List<Scene> threeOptions = new ArrayList<>(p.scenes());
        Scene q = threeOptions.get(1);
        threeOptions.set(1, new Scene(q.id(), q.kind(), q.title(), null, null, new Videos.Question("q1", 1, 2, "Stem", null, List.of("a", "b", "c"), 0, null),
                null, q.narration(), q.answerNarration(), null));
        Plan bad = new Plan(p.kind(), p.topicId(), p.topicTitle(), p.level(), p.sourceVersionId(), p.seoVersionId(), p.title(), p.language(), p.style(),
                p.aspect(), p.voice(), p.quiz(), threeOptions, null, true);
        assertTrue(assertThrows(ApiException.class, () -> quizRules.normalise(JSON.valueToTree(bad), null)).getMessage().contains("four options"));
        List<Scene> lessonScene = new ArrayList<>(p.scenes());
        lessonScene.add(new Scene("x", "SLIDE", "x", null, null, null, null, "x", null, null));
        Plan wrongKind = new Plan(p.kind(), p.topicId(), p.topicTitle(), p.level(), p.sourceVersionId(), p.seoVersionId(), p.title(), p.language(), p.style(),
                p.aspect(), p.voice(), p.quiz(), lessonScene, null, true);
        assertThrows(ApiException.class, () -> quizRules.normalise(JSON.valueToTree(wrongKind), null));
        Plan noVoice = new Plan(p.kind(), p.topicId(), p.topicTitle(), p.level(), p.sourceVersionId(), p.seoVersionId(), p.title(), p.language(), p.style(),
                p.aspect(), null, p.quiz(), p.scenes(), null, true);
        List<Check> c = quizRules.checks(quizRules.normalise(JSON.valueToTree(noVoice), null), Context.simple(UUID.randomUUID(), k -> null));
        assertFalse(ok(c, "voice"));
        assertTrue(ok(c, "questions"));
    }

    // ---- source checks -----------------------------------------------------------------------------

    @Test
    void slidesAndQuestionsMustBeAsApproved() {
        Plan p = VideoRules.read(new VideoRules.Lesson().normalise(JSON.valueToTree(VideoPlans.lesson(deck(), UUID.randomUUID(), null, null, List.of(), "EN",
                "16:9", VOICE)), null));
        JsonNode src = JSON.valueToTree(deck());
        assertEquals(List.of(), VideoStore.differences(p, src));
        Slides.Deck changed = deck();
        List<Slides.Slide> ss = new ArrayList<>(changed.slides());
        Slides.Slide b = ss.get(1);
        ss.set(1, new Slides.Slide(b.id(), b.type(), b.layout(), b.title(), null, List.of("Explain update anomalies", "Reach BCNF"), null, null, null, null,
                null, null, null, b.notes(), null));
        Slides.Deck d2 = new Slides.Deck(changed.topicId(), changed.topicTitle(), changed.level(), null, null, null, changed.style(), changed.title(), ss, null, null);
        assertEquals(List.of("“You will” differs from the presentation"), VideoStore.differences(p, JSON.valueToTree(d2)));

        Plan q = VideoPlans.quiz(bank(), UUID.randomUUID(), UUID.randomUUID(), null, "EN", "9:16", VOICE, 5, 10);
        assertEquals(List.of(), VideoStore.differences(q, JSON.valueToTree(bank())));
        Learning.Bank b2 = bank();
        List<Learning.Question> qs = new ArrayList<>(b2.questions());
        Learning.Question q1 = qs.get(0);
        qs.set(0, new Learning.Question(q1.id(), q1.style(), q1.difficulty(), q1.stem(), null, q1.options(), 2, q1.explanation(), null, null, null, null));
        assertEquals(List.of("Question 1 differs from the MCQ set"), VideoStore.differences(q, JSON.valueToTree(new Learning.Bank("t5", "x", null, null, false, qs))));
    }

    // ---- the timeline ------------------------------------------------------------------------------

    @Test
    void theTimelineFollowsTheNarrationAndWaitsForTheCountdown() {
        Plan p = VideoPlans.quiz(bank(), UUID.randomUUID(), UUID.randomUUID(), null, "EN", "9:16", VOICE, 5, 10);
        Narration n = narrate(p);
        Plan narrated = with(p, n);
        Map<String, Object> doc = VideoTimeline.build(narrated, f -> null);
        JsonNode d = JSON.valueToTree(doc);
        assertEquals(1080, d.path("width").asInt());
        assertEquals(1920, d.path("height").asInt());
        TimelineDocument.Summary s = TimelineDocument.check(d);
        assertEquals(java.util.Set.of(n.mediaId()), s.mediaIds());

        JsonNode scenes = d.path("tracks").get(1).path("clips");
        assertEquals(5, scenes.size());
        JsonNode q1 = scenes.get(1);
        Segment main = n.segments().stream().filter(x -> x.sceneId().equals("q1") && x.part().equals("MAIN")).findFirst().orElseThrow();
        Segment ans = n.segments().stream().filter(x -> x.sceneId().equals("q1") && x.part().equals("ANSWER")).findFirst().orElseThrow();
        JsonNode sc = q1.path("scene");
        assertEquals(0.4 + main.duration() + 0.3, sc.path("countdownStart").asDouble(), 0.001);
        assertEquals(sc.path("countdownStart").asDouble() + 5, sc.path("revealAt").asDouble(), 0.001);
        assertEquals(sc.path("revealAt").asDouble() + 0.6 + ans.duration() + 0.8, q1.path("duration").asDouble(), 0.002);
        assertFalse(sc.has("narration"), "the narration text isn't drawn");
        assertEquals(0, sc.path("question").path("answer").asInt());

        JsonNode audio = d.path("tracks").get(2).path("clips");
        JsonNode answerClip = audio.get(2);
        assertEquals(ans.start(), answerClip.path("trimStart").asDouble(), 0.001);
        assertEquals(q1.path("start").asDouble() + sc.path("answerStart").asDouble(), answerClip.path("start").asDouble(), 0.002);
        JsonNode captions = d.path("tracks").get(0).path("clips");
        assertTrue(captions.size() > 10);
        for (JsonNode c : captions) assertTrue(c.path("text").asText().split(" ").length <= 11 && c.path("caption").asBoolean());

        RenderValidator.Expected e = RenderValidator.expected(doc, new RenderSettings(1080, RenderSettings.Quality.HIGH), JSON);
        assertEquals(1080, e.width());
        assertEquals(1920, e.height());
        assertTrue(e.audio());
        assertEquals(VideoTimeline.duration(doc), e.durationSeconds(), 0.001);
    }

    @Test
    void aDiagramMustBeDrawnBeforeTheVideoIsBuilt() {
        Slides.Deck d = deck();
        List<Slides.Slide> ss = new ArrayList<>(d.slides());
        ss.add(new Slides.Slide("f", "FLOWCHART", "DIAGRAM", "Steps", null, null, null, null, null, new Slides.Figure("MERMAID", "flowchart LR\n A-->B"),
                null, "cap", null, "Walk the arrows.", null));
        Slides.Deck withFigure = new Slides.Deck(d.topicId(), d.topicTitle(), d.level(), null, null, null, d.style(), d.title(), ss, null, null);
        Plan p = VideoRules.read(new VideoRules.Lesson().normalise(JSON.valueToTree(VideoPlans.lesson(withFigure, UUID.randomUUID(), null, null, List.of(),
                "EN", "16:9", VOICE)), null));
        Plan narrated = with(p, narrate(p));
        assertThrows(ApiException.class, () -> VideoTimeline.build(narrated, f -> null));
        JsonNode doc = JSON.valueToTree(VideoTimeline.build(narrated, f -> "<svg/>"));
        assertEquals("<svg/>", doc.path("tracks").get(1).path("clips").get(3).path("scene").path("slide").path("figure").path("svg").asText());
        assertThrows(ApiException.class, () -> VideoTimeline.build(p, f -> null), "no narration, no timeline");
    }

    @Test
    void captionsBreakAtSentencesAndShareTheTimeByLength() {
        assertEquals(List.of("One two three four.", "Five six seven eight nine ten eleven twelve thirteen fourteen."),
                VideoTimeline.chunks("One two three four. Five six seven eight nine ten eleven twelve thirteen fourteen."));
        List<double[]> t = VideoTimeline.captionTimes("aaaa bbbb. cccc dddd eeee ffff gggg hhhh iiii jjjj kkkk", 10, 6);
        assertEquals(10, t.get(0)[0], 0.001);
        double end = t.get(t.size() - 1)[0] + t.get(t.size() - 1)[1];
        assertEquals(16, end, 0.01);
    }
}
