package com.leenax.studio.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.notes.NoteDoc;
import com.leenax.studio.slides.Slides.Deck;
import com.leenax.studio.slides.Slides.Slide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidesRulesTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** Answers like the document service: everything fits, except a bullet containing "TOO LONG". */
    static class FakeFitter extends SlideFitter {
        JsonNode last;

        FakeFitter() {
            super("");
        }

        @Override
        public JsonNode fit(JsonNode deck) {
            last = deck;
            ObjectNode out = JSON.createObjectNode();
            boolean all = true;
            ArrayNode slides = out.putArray("slides");
            for (JsonNode s : deck.path("slides")) {
                ObjectNode r = slides.addObject();
                r.put("id", s.path("id").asText());
                boolean over = s.path("bullets").toString().contains("TOO LONG");
                boolean tight = s.path("bullets").toString().contains("TIGHT");
                r.put("ok", !over);
                ArrayNode boxes = r.putArray("boxes");
                ObjectNode b = boxes.addObject();
                b.put("field", "bullets").put("size", over || tight ? 16 : 28).put("fits", !over).put("min", 16).put("max", 28);
                ArrayNode p = r.putArray("problems");
                if (over) p.add("Bullets: Too long");
                all &= !over;
            }
            out.put("ok", all);
            out.put("checked", true);
            return out;
        }
    }

    static Slide slide(String id, String type, String layout, String title, List<String> bullets) {
        return new Slide(id, type, layout, title, null, bullets, null, null, null, null, null, null, null, "Say this.", null);
    }

    static JsonNode deck(List<Slide> slides) {
        return JSON.valueToTree(new Deck("t1", "Normalisation", "BEGINNER", null, null, null, "DARK_TECH", "Normalisation", slides, null, "m"));
    }

    static List<Slide> good() {
        List<Slide> s = new ArrayList<>();
        s.add(new Slide("a", "TITLE", "TITLE", "Normalisation", "CSC2041", null, null, null, null, null, null, null, null, null, null));
        s.add(slide("b", "OBJECTIVES", null, "Objectives", List.of("Explain anomalies", "Reach 3NF")));
        s.add(slide("c", "SUMMARY", null, "Summary", List.of("Split tables", "Keep keys")));
        s.add(slide("d", "REFERENCES", "BULLETS", "References", List.of("Codd 1970")));
        return s;
    }

    static boolean ok(List<Check> checks, String code) {
        return checks.stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().ok();
    }

    @Test
    void normaliseKeepsOnlyWhatTheLayoutShowsAndStoresTheFit() {
        FakeFitter f = new FakeFitter();
        SlidesRules rules = new SlidesRules(f);
        Slide messy = new Slide("x", "definition", null, "  Key  ", "hidden subtitle", List.of("hidden bullet"), "Primary key", "Identifies a row.",
                null, null, null, null, null, "Notes", null);
        JsonNode out = rules.normalise(deck(List.of(messy)), null);
        JsonNode s = out.path("slides").get(0);
        assertEquals("DEFINITION", s.path("type").asText());
        assertEquals("DEFINITION", s.path("layout").asText());
        assertEquals("Key", s.path("title").asText());
        assertFalse(s.has("subtitle"));
        assertFalse(s.has("bullets"));
        assertEquals("Primary key", s.path("term").asText());
        assertTrue(out.path("fit").path("checked").asBoolean());
        assertEquals("DARK_TECH", out.path("style").asText());
        assertFalse(f.last.has("fit"), "the fit isn't sent to be fitted");
    }

    @Test
    void unknownTypesStylesAndRepeatedIdsAreMadeSafe() {
        SlidesRules rules = new SlidesRules(new FakeFitter());
        ObjectNode d = (ObjectNode) deck(List.of(slide("a", "NONSENSE", "WEIRD", "One", List.of("x")), slide("a", "SUMMARY", null, "Two", List.of("y"))));
        d.put("style", "NEON");
        JsonNode out = rules.normalise(d, null);
        assertEquals("LECTURE", out.path("style").asText());
        assertEquals("CONCEPT", out.path("slides").get(0).path("type").asText());
        assertEquals("BULLETS", out.path("slides").get(0).path("layout").asText());
        assertFalse(out.path("slides").get(0).path("id").asText().equals(out.path("slides").get(1).path("id").asText()));
    }

    @Test
    void tooLongTextIsRefusedNotCut() {
        SlidesRules rules = new SlidesRules(new FakeFitter());
        ApiException e = assertThrows(ApiException.class, () -> rules.normalise(deck(List.of(slide("a", "SUMMARY", null, "x".repeat(201), List.of()))), null));
        assertTrue(e.getMessage().contains("at most 200 characters"));
        assertThrows(ApiException.class, () -> rules.normalise(deck(List.of(slide("a", "SUMMARY", null, "T",
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11")))), null));
        ObjectNode noLevel = (ObjectNode) deck(good());
        noLevel.put("level", "EXPERT");
        assertThrows(ApiException.class, () -> rules.normalise(noLevel, null));
    }

    @Test
    void aGoodDeckPassesTheBlockingChecks() {
        SlidesRules rules = new SlidesRules(new FakeFitter());
        JsonNode c = rules.normalise(deck(good()), null);
        List<Check> checks = rules.checks(c, Context.simple(UUID.randomUUID(), k -> null));
        assertTrue(checks.stream().noneMatch(x -> x.blocking() && !x.ok()), checks.toString());
        assertTrue(ok(checks, "overflow"));
        assertTrue(ok(checks, "title-slide"));
        assertTrue(ok(checks, "references"));
        assertTrue(ok(checks, "speaker-notes"));
    }

    @Test
    void overflowAndAnUncheckedFitBlockApproval() {
        SlidesRules rules = new SlidesRules(new FakeFitter());
        List<Slide> s = good();
        s.set(1, slide("b", "OBJECTIVES", null, "Objectives", List.of("TOO LONG")));
        List<Check> checks = rules.checks(rules.normalise(deck(s), null), Context.simple(UUID.randomUUID(), k -> null));
        Check over = checks.stream().filter(x -> x.code().equals("overflow")).findFirst().orElseThrow();
        assertTrue(over.blocking() && !over.ok());
        assertTrue(over.message().contains("slide 2"), over.message());

        SlidesRules down = new SlidesRules(new SlideFitter(""));
        List<Check> c2 = down.checks(down.normalise(deck(good()), null), Context.simple(UUID.randomUUID(), k -> null));
        Check fit = c2.stream().filter(x -> x.code().equals("fit")).findFirst().orElseThrow();
        assertTrue(fit.blocking() && !fit.ok());
        assertTrue(fit.message().contains("isn't set up"), fit.message());
    }

    @Test
    void smallestSizeAndDenseSlidesAreWarnings() {
        SlidesRules rules = new SlidesRules(new FakeFitter());
        List<Slide> s = good();
        s.set(1, slide("b", "OBJECTIVES", null, "Objectives", List.of("TIGHT", "2", "3", "4", "5", "6", "7")));
        List<Check> checks = rules.checks(rules.normalise(deck(s), null), Context.simple(UUID.randomUUID(), k -> null));
        Check small = checks.stream().filter(x -> x.code().equals("small-text")).findFirst().orElseThrow();
        assertFalse(small.blocking());
        assertTrue(small.message().contains("slide 2"));
        assertFalse(ok(checks, "readability"));
    }

    @Test
    void layoutsNeedTheirContent() {
        SlidesRules rules = new SlidesRules(new FakeFitter());
        List<Slide> s = good();
        s.add(new Slide("e", "DIAGRAM", "DIAGRAM", "Flow", null, null, null, null, null, null, null, "cap", null, "n", null));
        s.add(new Slide("f", "COMPARISON", "TABLE", "Compare", null, null, null, null, null, null, null, null, null, "n", null));
        List<Check> checks = rules.checks(rules.normalise(deck(s), null), Context.simple(UUID.randomUUID(), k -> null));
        Check c = checks.stream().filter(x -> x.code().equals("complete")).findFirst().orElseThrow();
        assertTrue(c.blocking() && !c.ok());
        assertTrue(c.message().contains("slide 5 needs a diagram") && c.message().contains("slide 6 needs a table"), c.message());
    }

    @Test
    void diffNamesWhatChanged() {
        SlidesRules rules = new SlidesRules(new FakeFitter());
        JsonNode a = rules.normalise(deck(good()), null);
        List<Slide> s = new ArrayList<>(good());
        Slide moved = s.remove(2);
        s.add(1, moved);
        s.set(0, new Slide("a", "TITLE", "TITLE", "Normalisation", "Changed", null, null, null, null, null, null, null, null, null, null));
        s.remove(s.size() - 1);
        ObjectNode b = (ObjectNode) deck(s);
        b.put("style", "WHITEBOARD");
        List<String> d = rules.diff(a, rules.normalise(b, null));
        assertTrue(d.contains("Style: Whiteboard"), d.toString());
        assertTrue(d.contains("1 slide removed"), d.toString());
        assertTrue(d.contains("Slides reordered"), d.toString());
        assertTrue(d.contains("1 slide changed"), d.toString());
    }

    // ---- source checks -----------------------------------------------------------------------------

    static final String NOTES = "Normalisation removes update anomalies. First normal form needs atomic values. "
            + "Second normal form removes partial dependencies on a composite key. Codd proposed it in 1970.";

    @Test
    void slidesNotFoundInTheNotesAreListed() {
        List<Slide> s = List.of(
                slide("a", "CONCEPT", null, "1NF", List.of("First normal form needs atomic values", "Normalisation removes update anomalies")),
                slide("b", "CONCEPT", null, "Quantum", List.of("Qubits entangle photons", "Superposition collapses wavefunctions")),
                slide("c", "REVISION", null, "Quiz", List.of("Why do photons entangle?")));
        assertEquals(List.of(2), SlidesLogic.notInNotes(s, NOTES));
    }

    @Test
    void numbersNotInTheNotesAreListed() {
        List<Slide> s = List.of(slide("a", "CONCEPT", null, "History", List.of("Codd proposed it in 1970", "Adopted by 85% of systems")),
                new Slide("b", "EXAMPLE", "EXAMPLE", "SQL", null, null, null, "A key", null, null, null, null,
                        new Slides.Code("sql", "SELECT 42;"), null, null));
        assertEquals(List.of("85 (slide 1)"), SlidesLogic.numbersNotInNotes(s, NOTES));
    }

    @Test
    void referencesMustComeFromTheNotes() {
        List<Slide> s = List.of(slide("r", "REFERENCES", "BULLETS", "References", List.of("Codd, E. F. (1970)", "Made Up, A. (2024)")));
        assertEquals(List.of("Made Up, A. (2024)"), SlidesLogic.inventedReferences(s, List.of("Codd, E.F. (1970)")));
    }

    // ---- reading the AI's reply --------------------------------------------------------------------

    @Test
    void theAiReplyIsReadWithinLimitsAndItsReferencesAreDropped() throws Exception {
        JsonNode reply = JSON.readTree("""
                {"title": "Deck", "slides": [
                  {"type": "title", "title": "Normalisation", "subtitle": "Lesson 4"},
                  {"type": "FLOWCHART", "title": "Steps", "figureRef": "F1", "caption": "From the notes", "notes": "n"},
                  {"type": "DIAGRAM", "title": "Missing figure", "figureRef": "F9", "bullets": ["fallback"]},
                  {"type": "DEFINITION", "title": "Key", "term": "Key", "text": "Identifies a row."},
                  {"type": "COMPARISON", "title": "Forms", "table": {"headers": ["Form", "Removes"], "rows": [["1NF", "groups"]]}},
                  {"type": "REFERENCES", "title": "References", "bullets": ["Invented (2025)"]},
                  {"type": "SUMMARY", "title": "%s", "bullets": ["a", "b", "c", "d", "e", "f", "g", "h", "i"]}
                ]}""".formatted("x".repeat(300)));
        List<NoteDoc.Figure> figures = List.of(new NoteDoc.Figure("MATH", "x^2"), new NoteDoc.Figure("MERMAID", "flowchart LR\n A-->B"));
        List<Slide> s = SlidesAi.readDeck(reply, figures);
        assertEquals(6, s.size());
        assertEquals("TITLE", s.get(0).type());
        assertEquals("flowchart LR\n A-->B", s.get(1).figure().source());
        assertEquals("DIAGRAM", s.get(1).layout());
        assertEquals("BULLETS", s.get(2).layout());
        assertNull(s.get(2).figure());
        assertEquals("DEFINITION", s.get(3).layout());
        assertEquals("TABLE", s.get(4).layout());
        assertTrue(s.stream().noneMatch(x -> x.type().equals("REFERENCES")));
        assertEquals(120, s.get(5).title().length());
        assertEquals(8, s.get(5).bullets().size());
        assertNull(s.get(0).notes());
        assertEquals(new HashSet<>(s.stream().map(Slide::id).toList()).size(), s.size());
        // and what it reads is accepted by the rules
        assertNotNull(new SlidesRules(new FakeFitter()).normalise(deck(s), null));
    }

    @Test
    void theReferencesSlideIsTheNotesOwn() {
        assertNull(SlidesService.referencesSlide(List.of()));
        Slide r = SlidesService.referencesSlide(List.of("Codd (1970)", "Date (2003)"));
        assertEquals("REFERENCES", r.type());
        assertEquals(List.of("Codd (1970)", "Date (2003)"), r.bullets());
        assertEquals("Notes, the QA report and the SEO report", SlidesService.join(List.of("Notes", "the QA report", "the SEO report")));
    }
}
