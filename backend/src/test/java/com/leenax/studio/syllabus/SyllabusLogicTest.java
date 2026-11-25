package com.leenax.studio.syllabus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.syllabus.Content.Contradiction;
import com.leenax.studio.syllabus.Content.Node;
import com.leenax.studio.syllabus.Content.Source;
import com.leenax.studio.syllabus.Content.Syllabus;
import com.leenax.studio.syllabus.Content.Topic;
import com.leenax.studio.syllabus.Content.Topics;
import com.leenax.studio.syllabus.SyllabusAnalysis.SourceDoc;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyllabusLogicTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID DOC = UUID.randomUUID(), VER = UUID.randomUUID(), SYL1 = UUID.randomUUID(), SYL2 = UUID.randomUUID();

    static Node node(String id, String title, Node... kids) {
        return new Node(id, title, List.of(), List.of(kids));
    }

    static Syllabus syllabus(List<Node> structure, List<Contradiction> contradictions) {
        return new Syllabus(null, structure, List.of(), List.of(), contradictions, List.of(), null);
    }

    static Topic topic(String id, String title, List<String> items, List<String> prereq, Topic... kids) {
        return new Topic(id, title, items, true, "INTRODUCTORY", prereq, List.of(), null, List.of(kids));
    }

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the syllabus ------------------------------------------------------------------------------

    @Test
    void cleaningGivesIdsTrimsAndDropsEmptyItems() {
        Syllabus s = SyllabusRules.clean(syllabus(List.of(
                new Node(null, "  Normalisation\t ", null, List.of(new Node(null, "3NF", null, null), new Node(null, "  ", null, null))),
                new Node(null, "", null, null)), List.of()));
        assertEquals(1, s.structure().size());
        assertEquals("Normalisation", s.structure().get(0).title());
        assertEquals(1, s.structure().get(0).children().size());
        assertTrue(Content.ID.matcher(s.structure().get(0).id()).matches());
    }

    @Test
    void refusesTooDeepRepeatedIdsAndBadWeights() {
        Node tooDeep = node("a", "Unit", node("b", "Topic", node("c", "Sub", node("d", "Too deep"))));
        assertThrows(ApiException.class, () -> SyllabusRules.clean(syllabus(List.of(tooDeep), List.of())));
        assertThrows(ApiException.class, () -> SyllabusRules.clean(syllabus(List.of(node("x", "A"), node("x", "B")), List.of())));
        assertThrows(ApiException.class, () -> SyllabusRules.clean(new Syllabus(null, List.of(node("a", "A")), List.of(),
                List.of(new Content.Assessment(null, "Exam", "EXAM", 140.0, null, null)), List.of(), List.of(), null)));
    }

    @Test
    void contradictionsMustBeResolvedBeforeApproval() {
        Source s = new Source(DOC, VER, 2, "weight 60%", true);
        Syllabus open = syllabus(List.of(node("a", "Unit 1")), List.of(new Contradiction("c1", "Exam weight is 60% in D1 and 70% in D2", List.of(s), null)));
        List<Check> checks = SyllabusRules.checks(open);
        assertFalse(Review.passes(checks));
        assertEquals("1 of 1 contradictions between documents need a resolution",
                checks.stream().filter(c -> c.code().equals("contradictions")).findFirst().orElseThrow().message());
        Syllabus resolved = syllabus(List.of(node("a", "Unit 1")), List.of(new Contradiction("c1", "…", List.of(s), "The 2026 handbook is current: 60%")));
        assertTrue(Review.passes(SyllabusRules.checks(resolved)));
        assertFalse(Review.passes(SyllabusRules.checks(syllabus(List.of(), List.of()))));
    }

    @Test
    void weightsAndMissingSourcesAreWarningsOnly() {
        Syllabus s = new Syllabus(null, List.of(node("a", "Unit")), List.of(),
                List.of(new Content.Assessment("e", "Exam", "EXAM", 60.0, null, List.of()), new Content.Assessment("c", "Coursework", "COURSEWORK", 30.0, null, List.of())),
                List.of(), List.of(), null);
        List<Check> checks = SyllabusRules.checks(s);
        assertTrue(Review.passes(checks));
        assertTrue(checks.stream().anyMatch(c -> c.code().equals("weights") && c.message().contains("90%")));
        assertTrue(checks.stream().anyMatch(c -> c.code().equals("sources") && !c.ok() && !c.blocking()));
    }

    @Test
    void diffNamesWhatChanged() {
        Syllabus a = syllabus(List.of(node("a", "Normalisation"), node("b", "Indexes")), List.of());
        Syllabus b = syllabus(List.of(node("a", "Normalisation and 3NF"), node("c", "Transactions")), List.of());
        assertEquals(List.of("Renamed “Normalisation” to “Normalisation and 3NF”", "Added “Transactions”", "Removed “Indexes”"),
                SyllabusRules.diff(a, b));
    }

    // ---- the AI's answer ---------------------------------------------------------------------------

    static List<SourceDoc> docs() {
        Map<Integer, String> pages = new LinkedHashMap<>();
        pages.put(1, "CSC2041 Database Systems\nLecture 3: Normalisation and functional\ndependencies.");
        pages.put(2, "தரவுத்தள முறைமைகள் பற்றிய இந்த விரிவுரை சாதாரணமாக்கல் பற்றி விளக்குகிறது.");
        return List.of(new SourceDoc("D1", DOC, VER, "Lecture 3", 1, pages));
    }

    @Test
    void quotesAreFoundAcrossLineBreaksCaseAndPunctuationInBothLanguages() {
        String page = docs().get(0).pages().get(1);
        assertTrue(SyllabusAnalysis.contains(page, "normalisation and functional dependencies"));
        assertTrue(SyllabusAnalysis.contains(docs().get(0).pages().get(2), "முறைமைகள் பற்றிய"));
        assertFalse(SyllabusAnalysis.contains(page, "query optimisation"));
        // a Tamil quote cut inside a word must not match a different word
        assertFalse(SyllabusAnalysis.contains(docs().get(0).pages().get(2), "முறை பற்றிய"));
        assertFalse(SyllabusAnalysis.contains(page, "a"));
    }

    @Test
    void readingTheAnswerChecksEveryCitation() {
        JsonNode reply = json("""
                {"summary": "A database module.",
                 "structure": [{"title": "Normalisation", "sources": [
                     {"doc": "D1", "page": 1, "quote": "Normalisation and functional dependencies"},
                     {"doc": "D1", "page": 9, "quote": "made up page"},
                     {"doc": "D7", "page": 1, "quote": "no such document"}],
                   "children": [{"title": "சாதாரணமாக்கல்", "sources": [{"doc": "d1", "page": 2, "quote": "இந்த விரிவுரை சாதாரணமாக்கல்"}]},
                                {"title": "Invented", "sources": [{"doc": "D1", "page": 2, "quote": "words that are not there"}]}]}],
                 "outcomes": [{"code": "LO1", "text": "Normalise a schema", "sources": []}],
                 "assessments": [{"title": "Exam", "kind": "exam", "weight": 60}, {"title": "Odd", "kind": "DANCE", "weight": 500}],
                 "contradictions": [{"description": "Weights differ", "sources": [{"doc": "D1", "page": 1, "quote": "CSC2041 Database Systems"}]}]}
                """);
        Syllabus s = SyllabusAnalysis.read(reply, docs(), "gpt-test");
        Node unit = s.structure().get(0);
        assertEquals(1, unit.sources().size(), "citations to missing pages and documents are removed");
        assertTrue(unit.sources().get(0).found());
        assertTrue(unit.children().get(0).sources().get(0).found());
        assertFalse(unit.children().get(1).sources().get(0).found());
        assertEquals(Integer.valueOf(2), s.analysis().citationsRemoved());
        assertEquals(Integer.valueOf(4), s.analysis().citations());
        assertEquals(Integer.valueOf(3), s.analysis().citationsFound());
        assertEquals("EXAM", s.assessments().get(0).kind());
        assertEquals("OTHER", s.assessments().get(1).kind());
        assertEquals(null, s.assessments().get(1).weight());
        assertEquals("D1", s.documents().get(0).label());
        assertEquals(null, s.contradictions().get(0).resolution(), "a person resolves contradictions, not the AI");
    }

    @Test
    void thePromptLabelsDocumentsAndPages() {
        String p = SyllabusAnalysis.prompt(docs(), "Focus on the 2026 module");
        assertTrue(p.startsWith("Guidance from the lecturer"));
        assertTrue(p.contains("<document id=\"D1\" title=\"Lecture 3\">"));
        assertTrue(p.contains("<page n=\"2\">\nதரவுத்தள"));
        assertTrue(SyllabusAnalysis.SYSTEM.contains("Don't translate"));
    }

    // ---- topics ------------------------------------------------------------------------------------

    @Test
    void firstTopicListMirrorsTheSyllabus() {
        Syllabus s = syllabus(List.of(node("u1", "Unit 1", node("t1", "Keys"), node("t2", "3NF"))), List.of());
        Topics t = TopicRules.rebase(null, s, SYL1);
        assertEquals(SYL1, t.syllabusVersionId());
        assertEquals("Unit 1", t.nodes().get(0).title());
        assertEquals(List.of("Keys", "3NF"), t.nodes().get(0).children().stream().map(Topic::title).toList());
        assertEquals(List.of("t1"), t.nodes().get(0).children().get(0).syllabusItemIds());
    }

    @Test
    void updatingKeepsEditsAddsNewItemsAndFlagsRemovedOnes() {
        Topics mine = new Topics(SYL1, List.of(
                topic("a", "Unit one: keys and forms", List.of("u1"), List.of(),
                        topic("b", "Keys and 3NF (merged)", List.of("t1", "t2"), List.of()),
                        topic("c", "Indexing", List.of("t9"), List.of("b"))),
                topic("own", "My extra revision session", List.of(), List.of())));
        Syllabus newer = syllabus(List.of(node("u1", "Unit 1", node("t1", "Keys"), node("t2", "3NF"), node("t3", "BCNF"))), List.of());
        Topics t = TopicRules.rebase(mine, newer, SYL2);
        assertEquals(SYL2, t.syllabusVersionId());
        Topic unit = t.nodes().get(0);
        assertEquals("Unit one: keys and forms", unit.title(), "renames are kept");
        assertEquals(List.of("Keys and 3NF (merged)", "Indexing", "BCNF"), unit.children().stream().map(Topic::title).toList());
        assertEquals(Boolean.TRUE, unit.children().get(1).notInSyllabus());
        assertEquals(null, unit.children().get(0).notInSyllabus());
        assertEquals("My extra revision session", t.nodes().get(1).title());
        assertEquals(null, t.nodes().get(1).notInSyllabus(), "topics added by hand aren't flagged");
        assertEquals(List.of("b"), unit.children().get(1).prerequisites());
    }

    @Test
    void topicChecksFollowTheApprovedSyllabus() {
        Topics t = new Topics(SYL1, List.of(topic("a", "Keys", List.of("t1"), List.of())));
        assertTrue(Review.passes(TopicRules.checks(t, SYL1)));
        assertFalse(Review.passes(TopicRules.checks(t, SYL2)));
        assertFalse(Review.passes(TopicRules.checks(t, null)));
    }

    @Test
    void prerequisiteLoopsAndUnselectedPrerequisitesBlockApproval() {
        Topics loop = new Topics(SYL1, List.of(topic("a", "A", List.of(), List.of("c")), topic("b", "B", List.of(), List.of("a")),
                topic("c", "C", List.of(), List.of("b"))));
        Check order = TopicRules.checks(loop, SYL1).stream().filter(c -> c.code().equals("order")).findFirst().orElseThrow();
        assertFalse(order.ok());
        assertEquals("Prerequisites go round in a loop: A → C → B → A", order.message());

        Topic off = new Topic("b", "B", List.of(), false, null, List.of(), List.of(), null, List.of());
        Topics unselected = new Topics(SYL1, List.of(topic("a", "A", List.of(), List.of("b")), off));
        assertFalse(Review.passes(TopicRules.checks(unselected, SYL1)));
    }

    @Test
    void topicCleaningRefusesBadPrerequisitesAndLevels() {
        assertThrows(ApiException.class, () -> TopicRules.clean(new Topics(SYL1, List.of(topic("a", "A", List.of(), List.of("a"))))));
        assertThrows(ApiException.class, () -> TopicRules.clean(new Topics(SYL1, List.of(topic("a", "A", List.of(), List.of("ghost"))))));
        assertThrows(ApiException.class, () -> TopicRules.clean(new Topics(SYL1, List.of(
                new Topic("a", "A", List.of(), true, "EXPERT", List.of(), List.of(), null, List.of())))));
        assertThrows(ApiException.class, () -> new TopicRules().normalise(JSON.valueToTree(new Topics(null, List.of())), null));
    }

    @Test
    void theSyllabusVersionOfATopicListComesFromTheServerNotTheBrowser() {
        JsonNode sent = JSON.valueToTree(new Topics(SYL2, List.of(topic("a", "A", List.of(), List.of()))));
        JsonNode stored = new TopicRules().normalise(sent, SYL1);
        assertEquals(SYL1.toString(), stored.path("syllabusVersionId").asText());
    }

    // ---- hashing -----------------------------------------------------------------------------------

    @Test
    void theSameContentAlwaysHashesTheSame() {
        String a = Review.canonical(json("{\"b\": 1, \"a\": {\"y\": [2, 1], \"x\": \"தமிழ்\"}}"));
        String b = Review.canonical(json("{\"a\": {\"x\": \"தமிழ்\", \"y\": [2, 1]}, \"b\": 1}"));
        assertEquals(a, b);
        assertEquals(Review.hash(a), Review.hash(b));
        assertNotEquals(Review.hash(a), Review.hash(Review.canonical(json("{\"a\": {\"x\": \"தமிழ்\", \"y\": [1, 2]}, \"b\": 1}"))));
        assertEquals(64, Review.hash(a).length());
    }
}
