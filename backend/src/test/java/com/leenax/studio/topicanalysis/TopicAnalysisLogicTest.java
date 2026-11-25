package com.leenax.studio.topicanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.notes.MaterialsAi;
import com.leenax.studio.notes.MaterialsAi.Passage;
import com.leenax.studio.notes.MaterialsAi.TopicBrief;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicAnalysisLogicTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID DOC = UUID.randomUUID(), VER = UUID.randomUUID(), TOPICS = UUID.randomUUID();
    static final TopicBrief BRIEF = new TopicBrief("t1", "Normalisation", "Databases", List.of("1NF", "2NF"), List.of(), List.of("LO2"), "INTERMEDIATE");
    static final List<Passage> PAGES = List.of(new Passage("D1", DOC, VER, "Week 3 slides", 4,
            "A relation is in first normal form when every attribute holds a single atomic value."));

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String REPLY = """
            {"summary": "Normal forms remove redundancy.",
             "difficulty": {"level": "intermediate", "why": "Needs functional dependencies."},
             "prerequisites": [{"text": "Keys", "basis": "GENERAL"}],
             "objectives": [
               {"id": "O1", "text": "Define 1NF", "bloom": "remember", "outcomes": ["LO2"],
                "sources": [{"doc": "D1", "page": 4, "quote": "every attribute holds a single atomic value"}]},
               {"id": "O2", "text": "Normalise a table to 3NF", "bloom": "APPLY",
                "sources": [{"doc": "D9", "page": 1, "quote": "invented"}]}],
             "concepts": [{"id": "C1", "term": "Atomic value", "definition": "One value", "basis": "DOCUMENT",
                "sources": [{"doc": "D1", "page": 4, "quote": "not on the page"}]}],
             "misconceptions": [{"wrong": "1NF means no duplicates", "right": "1NF means atomic values", "concept": "C1", "basis": "GENERAL"}],
             "examples": [{"title": "Student table", "kind": "WORKED", "concepts": ["C1", "C7"]}],
             "examRelevance": [],
             "mcqPlan": [{"objective": "O1", "count": 8, "difficulties": {"beginner": 8}, "styles": ["exam"]},
                         {"objective": "O2", "count": 12, "difficulties": {"INTERMEDIATE": 6, "ADVANCED": 6}, "styles": ["SCENARIO", "BOGUS"]},
                         {"objective": "O9", "count": 3}],
             "videoPlan": {"hook": "Why does the same address appear 40 times?",
                "scenes": [{"title": "The problem", "goal": "O1", "shows": "EXAMPLE", "seconds": 120, "concepts": ["C1"]},
                           {"title": "Fixing it", "goal": "O2", "shows": "DIAGRAM", "seconds": 300},
                           {"title": "Recap", "shows": "SUMMARY", "seconds": 60}]},
             "gaps": ["The slides don't cover BCNF"], "questions": []}
            """;

    static TopicAnalysis analysis() {
        TopicAnalysis raw = TopicAnalysisAi.read(json(REPLY), BRIEF, TOPICS, PAGES, 20, 8, "test-model");
        return TopicAnalysisRules.tidy(raw, new ArrayList<>());
    }

    @Test
    void readsTheReplyAndChecksQuotesAgainstThePages() {
        TopicAnalysis raw = TopicAnalysisAi.read(json(REPLY), BRIEF, TOPICS, PAGES, 20, 8, "test-model");
        assertEquals("t1", raw.topicId());
        assertEquals("INTERMEDIATE", raw.difficulty().level());
        assertEquals(2, (int) raw.analysis().quotesKept());
        assertEquals(1, (int) raw.analysis().quotesFound());
        assertEquals(1, (int) raw.analysis().quotesRemoved(), "a citation of a page it wasn't given is removed");
        assertTrue(raw.objectives().get(0).sources().get(0).found());
        assertFalse(raw.concepts().get(0).sources().get(0).found());
        assertEquals(1, (int) raw.misconceptions().get(0).number());
        assertEquals(TOPICS, raw.topicsVersionId());
    }

    @Test
    void tidyDropsUnknownLabelsAndReferences() {
        List<String> notes = new ArrayList<>();
        TopicAnalysis a = TopicAnalysisRules.tidy(TopicAnalysisAi.read(json(REPLY), BRIEF, TOPICS, PAGES, 20, 8, "m"), notes);
        assertEquals(2, a.mcqPlan().size(), "the row for an objective that doesn't exist is left out");
        assertEquals(List.of("SCENARIO"), a.mcqPlan().get(1).styles());
        assertEquals(List.of("C1"), a.examples().get(0).concepts());
        assertNull(a.videoPlan().scenes().get(2).goal());
        assertTrue(notes.stream().anyMatch(n -> n.contains("question plan row")));
    }

    @Test
    void tidyNumbersMissingAndRepeatedIds() {
        List<String> notes = new ArrayList<>();
        TopicAnalysis raw = TopicAnalysisAi.read(json("""
                {"objectives": [{"text": "a"}, {"id": "O1", "text": "b"}, {"id": "O1", "text": "c"}, {"id": "bad id!", "text": "d"}]}"""),
                BRIEF, TOPICS, PAGES, 20, 8, "m");
        List<String> ids = TopicAnalysisRules.tidy(raw, notes).objectives().stream().map(TopicAnalysis.Objective::id).toList();
        assertEquals(4, Set.copyOf(ids).size());
        ids.forEach(id -> assertTrue(id.matches("[A-Za-z0-9_-]{1,16}")));
    }

    @Test
    void theTidiedAnswerCanBeSaved() {
        JsonNode saved = new TopicAnalysisRules().normalise(JSON.valueToTree(analysis()), TOPICS);
        assertEquals("O1", saved.path("objectives").get(0).path("id").asText());
        assertEquals(TOPICS.toString(), saved.path("topicsVersionId").asText());
    }

    @Test
    void savingRefusesWhatDoesNotFitInsteadOfChangingIt() {
        TopicAnalysisRules rules = new TopicAnalysisRules();
        JsonNode ok = JSON.valueToTree(analysis());
        assertThrows(ApiException.class, () -> rules.normalise(ok, null), "needs the topic list it was made from");
        var badRef = (com.fasterxml.jackson.databind.node.ObjectNode) ok.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) badRef.path("mcqPlan").get(0)).put("objective", "O77");
        assertThrows(ApiException.class, () -> rules.normalise(badRef, TOPICS));
        var longText = (com.fasterxml.jackson.databind.node.ObjectNode) ok.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) longText.path("objectives").get(0)).put("text", "x".repeat(2001));
        assertThrows(ApiException.class, () -> rules.normalise(longText, TOPICS));
        var badScene = (com.fasterxml.jackson.databind.node.ObjectNode) ok.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) badScene.path("videoPlan").path("scenes").get(0)).put("seconds", 2);
        assertThrows(ApiException.class, () -> rules.normalise(badScene, TOPICS));
    }

    static Review.Context ctx(String topicsJson) {
        return new Review.Context(UUID.randomUUID(), null, k -> TOPICS, k -> k == Kind.TOPICS ? json(topicsJson) : null, k -> List.of(), List::of);
    }

    static final String TOPIC_LIST = """
            {"nodes": [{"id": "ch1", "title": "Databases", "selected": true, "children": [{"id": "t1", "title": "Normalisation", "selected": true}]}]}""";

    static Check check(List<Check> checks, String code) {
        return checks.stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void checksBlockAPlanThatDoesNotAddUp() {
        TopicAnalysisRules rules = new TopicAnalysisRules();
        List<Check> checks = rules.checks(JSON.valueToTree(analysis()), ctx(TOPIC_LIST));
        assertTrue(check(checks, "topic").ok());
        assertTrue(check(checks, "question-plan").ok(), "8 + 12 = 20");
        assertTrue(check(checks, "video-plan").ok(), "8 minutes planned, 8 asked");
        assertFalse(check(checks, "sources").ok());
        assertFalse(check(checks, "sources").blocking());
        assertFalse(check(checks, "open-points").blocking());
        assertTrue(Review.passes(checks));

        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(analysis());
        changed.put("mcqCount", 25);
        List<Check> off = rules.checks(changed, ctx(TOPIC_LIST));
        assertFalse(check(off, "question-plan").ok());
        assertFalse(Review.passes(off));
    }

    @Test
    void checksBlockATopicThatLeftTheList() {
        String unselected = TOPIC_LIST.replace("\"title\": \"Normalisation\", \"selected\": true", "\"title\": \"Normalisation\", \"selected\": false");
        List<Check> checks = new TopicAnalysisRules().checks(JSON.valueToTree(analysis()), ctx(unselected));
        assertFalse(check(checks, "topic").ok());
        assertFalse(Review.passes(checks));
    }

    @Test
    void videoLengthFarFromTheTargetIsAWarning() {
        var a = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(analysis());
        a.put("videoMinutes", 15);
        Check c = check(new TopicAnalysisRules().checks(a, ctx(TOPIC_LIST)), "video-plan");
        assertFalse(c.ok());
        assertFalse(c.blocking());
    }

    @Test
    void diffSaysWhatChanged() {
        TopicAnalysis a = analysis();
        var b = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(a);
        ((com.fasterxml.jackson.databind.node.ObjectNode) b.path("mcqPlan").get(0)).put("count", 9);
        List<String> d = new TopicAnalysisRules().diff(JSON.valueToTree(a), b);
        assertTrue(d.contains("question plan changed"));
    }

    @Test
    void unitsAreTopicsUnderChaptersOrChaptersWithoutTopics() {
        List<String[]> u = TopicAnalysisService.units(json("""
                {"nodes": [{"id": "a", "title": "A", "children": [{"id": "a1", "title": "A1"}, {"id": "a2", "title": "A2", "selected": false}]},
                           {"id": "b", "title": "B", "children": [{"id": "b1", "title": "B1", "selected": false}]},
                           {"id": "c", "title": "C", "selected": false, "children": [{"id": "c1", "title": "C1"}]}]}"""));
        assertEquals(List.of("a1", "b"), u.stream().map(x -> x[0]).toList());
        assertEquals("A", u.get(0)[2]);
    }

    // ---- plans in the question and presentation prompts ---------------------------------------------------------

    @Test
    void planScalesToTheCountAsked() {
        TopicAnalysis a = analysis();
        assertEquals(Map.of("O1", 8, "O2", 12), AnalysisPlans.scale(a.mcqPlan(), 20));
        Map<String, Integer> ten = AnalysisPlans.scale(a.mcqPlan(), 10);
        assertEquals(10, ten.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(4, ten.get("O1"));
        Map<String, Integer> one = AnalysisPlans.scale(a.mcqPlan(), 1);
        assertEquals(1, one.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void questionPromptCarriesTheObjectivesAndMisconceptions() {
        String p = AnalysisPlans.questionPlan(analysis(), 20);
        assertTrue(p.contains("O1 (8 questions, remember): Define 1NF"));
        assertTrue(p.contains("O2 (12 questions, apply)"));
        assertTrue(p.contains("1NF means no duplicates"));
        assertTrue(p.contains("\"objective\""));
    }

    @Test
    void plannedQuestionsAreTaggedWithTheirObjective() {
        JsonNode reply = json("""
                {"questions": [{"style": "EXAM", "difficulty": "BEGINNER", "stem": "Which of these tables is in first normal form?",
                  "options": ["One with repeating groups", "One with a list in a cell", "One with atomic values", "One with no key"],
                  "answer": "C", "explanation": "1NF needs atomic values.", "objective": "O1", "subtopic": "1NF"},
                 {"style": "EXAM", "difficulty": "BEGINNER", "stem": "What does 2NF remove from a relation?",
                  "options": ["Partial dependencies", "Transitive dependencies", "Repeating groups", "Multivalued dependencies"],
                  "answer": "A", "explanation": "2NF removes partial dependencies.", "objective": "O9", "subtopic": "2NF"}]}""");
        MaterialsAi.Drafted d = MaterialsAi.readQuestions(reply, List.of(), false, "Notes v1", Set.of("O1", "O2"));
        assertEquals(2, d.accepted().size(), String.join("; ", d.dropped()));
        assertEquals("O1: 1NF", d.accepted().get(0).subtopic());
        assertEquals("2NF", d.accepted().get(1).subtopic(), "an objective that isn't in the plan isn't added");
        assertEquals("O1 1/8, O2 0/12", AnalysisPlans.coverage(analysis(), 20, d.accepted().stream().map(q -> q.subtopic()).toList()));
    }

    @Test
    void deckPromptCarriesScenesAndTiming() {
        String p = AnalysisPlans.deckPlan(analysis(), 150);
        assertTrue(p.contains("Why does the same address appear 40 times?"));
        assertTrue(p.contains("1. The problem — 120 s, about 300 words, shows: example. Goal: Define 1NF. Concepts: Atomic value"));
        assertTrue(p.contains("about 8 minutes"));
        assertEquals(2 + 2 + 2 + 1, AnalysisPlans.slidesFor(analysis(), 14));
    }

    @Test
    void speakingPaceFollowsTheLanguage() {
        assertEquals(150, AnalysisPlans.wordsPerMinute("Normalisation removes redundancy from relations."));
        assertEquals(120, AnalysisPlans.wordsPerMinute("இயல்பாக்கம் தரவுத்தளத்தில் மீள்வருகையை நீக்குகிறது"));
        assertEquals(150, AnalysisPlans.wordsPerMinute(null));
    }
}
