package com.leenax.studio.notes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.notes.Learning.Bank;
import com.leenax.studio.notes.Learning.Notes;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.notes.MaterialsAi.Passage;
import com.leenax.studio.notes.MaterialsAi.TopicBrief;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialsLogicTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Question q(String id, String stem, List<String> options, Integer answer) {
        return new Question(id, "EXAM", "INTERMEDIATE", stem, null, options, answer, "Because the notes say so.", null, null, null, "UNVERIFIED");
    }

    // ---- the editor document -----------------------------------------------------------------------

    @Test
    void keepsAllowedBlocksAndDropsUnknownAttributes() {
        JsonNode doc = json("""
                {"type":"doc","content":[
                 {"type":"heading","attrs":{"level":9,"onclick":"x"},"content":[{"type":"text","text":"Keys"}]},
                 {"type":"paragraph","content":[{"type":"text","text":"See ","marks":[]},
                   {"type":"text","text":"docs","marks":[{"type":"link","attrs":{"href":"https://example.org/k","target":"_blank"}}]},
                   {"type":"mathInline","attrs":{"latex":"x^2"}}]},
                 {"type":"codeBlock","attrs":{"language":"SQL"},"content":[{"type":"text","text":"select 1;"}]},
                 {"type":"mermaid","attrs":{"code":"flowchart TD; A-->B"}},
                 {"type":"table","content":[{"type":"tableRow","content":[{"type":"tableHeader","attrs":{"colspan":99},"content":[{"type":"paragraph"}]}]}]}]}
                """);
        JsonNode clean = NoteDoc.clean(doc);
        assertEquals(4, clean.at("/content/0/attrs/level").asInt());
        assertFalse(clean.at("/content/0/attrs").has("onclick"));
        assertFalse(clean.at("/content/1/content/1/marks/0/attrs").has("target"));
        assertEquals("sql", clean.at("/content/2/attrs/language").asText());
        assertEquals(20, clean.at("/content/4/content/0/content/0/attrs/colspan").asInt());
        assertEquals(List.of(new NoteDoc.Figure("MATH", "x^2"), new NoteDoc.Figure("MERMAID", "flowchart TD; A-->B")), NoteDoc.figures(clean));
    }

    @Test
    void refusesScriptsUnknownBlocksAndOddLinks() {
        assertThrows(ApiException.class, () -> NoteDoc.clean(json("{\"type\":\"doc\",\"content\":[{\"type\":\"iframe\"}]}")));
        assertThrows(ApiException.class, () -> NoteDoc.clean(json("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"x","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]}]}""")));
        assertThrows(ApiException.class, () -> NoteDoc.clean(json("""
                {"type":"doc","content":[{"type":"mediaImage","attrs":{"assetId":"../../etc/passwd"}}]}""")));
        assertThrows(ApiException.class, () -> NoteDoc.clean(json("{\"type\":\"paragraph\"}")));
    }

    @Test
    void buildsTheDocumentFromTheAisSections() {
        JsonNode sections = json("""
                [{"heading":"Introduction","blocks":[{"type":"paragraph","text":"A **key** identifies a row; `PRIMARY KEY` and $n^2$ and *care*."}]},
                 {"heading":"Comparison tables","blocks":[{"type":"table","header":["Key","Unique"],"rows":[["Primary","Yes"],["Foreign","No"]]}]},
                 {"heading":"Flowcharts","blocks":[{"type":"diagram","mermaid":"flowchart TD\\nA-->B"},{"type":"equation","latex":"\\\\frac{a}{b}"}]},
                 {"heading":"Quick revision notes","blocks":[{"type":"bullets","items":["முதன்மை விசை","Foreign key"]},{"type":"unknown"}]}]
                """);
        JsonNode doc = NoteDoc.clean(NoteDoc.fromSections("Keys", sections));
        assertEquals(List.of("Keys", "Introduction", "Comparison tables", "Flowcharts", "Quick revision notes"), NoteDoc.headings(doc));
        JsonNode para = doc.at("/content/2/content");
        assertEquals("bold", para.get(1).at("/marks/0/type").asText());
        assertEquals("code", para.get(3).at("/marks/0/type").asText());
        assertEquals("mathInline", para.get(5).path("type").asText());
        assertEquals("italic", para.get(7).at("/marks/0/type").asText());
        String text = NoteDoc.plainText(doc);
        assertTrue(text.contains("Primary | Yes |"), text);
        assertTrue(text.contains("$$\\frac{a}{b}$$"));
        assertTrue(text.contains("[diagram]"));
        assertTrue(text.contains("முதன்மை விசை"));
    }

    // ---- notes from the AI -------------------------------------------------------------------------

    @Test
    void notesReferencesAreCheckedAgainstTheirPages() {
        UUID d = UUID.randomUUID(), v = UUID.randomUUID();
        List<Passage> passages = List.of(new Passage("D1", d, v, "Handbook", 4, "A primary key uniquely identifies each row of a table."));
        JsonNode reply = json("""
                {"title":"Keys","sections":[{"heading":"Introduction","blocks":[{"type":"paragraph","text":"Text"}]}],
                 "references":[{"doc":"D1","page":4,"quote":"uniquely identifies each row"},{"doc":"D1","page":4,"quote":"again"},
                               {"doc":"D1","page":9,"quote":"made up"},{"doc":"D2","page":1,"quote":"x"}]}""");
        Notes n = MaterialsAi.readNotes(reply, new TopicBrief("t1", "Keys", null, List.of(), List.of(), List.of(), null), "EXAM", passages, "m");
        assertEquals(1, n.references().size(), "duplicates and pages that weren't given are dropped");
        assertTrue(n.references().get(0).found());
        assertEquals("Handbook, page 4", n.references().get(0).label());
    }

    @Test
    void notesPromptCarriesLevelTopicAndCopyrightRule() {
        String p = MaterialsAi.notesPrompt(new TopicBrief("t", "Keys", "Unit 1", List.of("Primary keys"), List.of("Tables"), List.of("LO1 Design"), "INTERMEDIATE"),
                "EXAM", List.of(), "Use Sri Lankan examples");
        assertTrue(p.contains("Level: Exam-focused, for exam revision"));
        assertTrue(p.contains("Subtopics to cover: Primary keys"));
        assertTrue(p.contains("Students already know: Tables"));
        assertTrue(p.contains("(none found"));
        assertTrue(MaterialsAi.notesSystem().contains("Never copy more than a short phrase"));
        assertTrue(MaterialsAi.notesSystem().contains("Common mistakes; Misconceptions; Memory techniques"));
    }

    // ---- question rules ----------------------------------------------------------------------------

    @Test
    void aGoodQuestionHasNoProblems() {
        assertTrue(McqRules.problems(q("a", "Which key identifies a row?", List.of("Primary", "Foreign", "Candidate", "Super"), 0), false).isEmpty());
    }

    @Test
    void everyRuleIsEnforced() {
        assertEquals(List.of("options repeat"), McqRules.problems(q("a", "Q?", List.of("Primary", " primary ", "Foreign", "Super"), 0), false));
        assertEquals(List.of("needs four options"), McqRules.problems(q("a", "Q?", List.of("A", "", "C", "D"), 0), false));
        assertEquals(List.of("no correct answer marked"), McqRules.problems(q("a", "Q?", List.of("A", "B", "C", "D"), null), false));
        assertEquals(List.of("no question text", "no explanation"),
                McqRules.problems(new Question("a", "EXAM", null, " ", null, List.of("A", "B", "C", "D"), 1, null, null, null, null, null), false));
    }

    @Test
    void allOrNoneOfTheAboveIsCaughtInEnglishAndTamil() {
        for (String bad : List.of("All of the above", "none of these", "Both of them", "all the above", "மேற்கண்ட அனைத்தும்", "எதுவுமில்லை")) {
            Question x = q("a", "Q?", List.of("One", "Two", "Three", bad), 0);
            assertEquals(List.of("“all/none of the above” option"), McqRules.problems(x, false), bad);
            assertTrue(McqRules.problems(x, true).isEmpty(), "allowed when the bank allows it");
        }
        assertTrue(McqRules.problems(q("a", "Q?", List.of("Allocation", "Nonexistent", "Above ground", "Bothersome"), 0), false).isEmpty());
    }

    @Test
    void similarityFindsRewordedRepeatsInBothLanguages() {
        assertTrue(McqRules.similarity("Which key uniquely identifies each row in a table?", "Which key uniquely identifies each row in a table") >= McqRules.DUPLICATE);
        assertTrue(McqRules.similarity("Which key uniquely identifies each row in a table?", "What does a foreign key reference?") < McqRules.SIMILAR);
        assertTrue(McqRules.similarity("எந்த விசை ஒவ்வொரு வரிசையையும் அடையாளம் காட்டுகிறது?", "எந்த விசை ஒவ்வொரு வரிசையையும் அடையாளம் காட்டுகிறது") >= McqRules.DUPLICATE);
    }

    @Test
    void bankChecksBlockBadQuestionsAndRepeats() {
        Bank b = new Bank("t", "Keys", UUID.randomUUID(), "EXAM", false, List.of(
                q("a", "Which key uniquely identifies each row?", List.of("Primary", "Foreign", "Candidate", "Super"), 0),
                q("b", "Which key uniquely identifies each row?", List.of("Primary", "Foreign", "Alternate", "Super"), 0),
                q("c", "Which is a DDL command?", List.of("CREATE", "SELECT", "None of the above", "UPDATE"), 0)));
        List<Check> checks = McqRules.questionChecks(b);
        assertFalse(Review.passes(checks));
        assertTrue(checks.stream().anyMatch(c -> c.message().equals("The same question twice: question 1 and question 2")), checks.toString());
        assertTrue(checks.stream().anyMatch(c -> c.message().equals("“all/none of the above” option: question 3")), checks.toString());
    }

    @Test
    void bankCleaningKeepsFourOptionSlotsAndAValidKey() {
        Bank in = new Bank("t", "Keys", null, "EXAM", null, List.of(q(null, "Q?", List.of("A", "B", "C"), 0)));
        assertThrows(ApiException.class, () -> new McqRules().normalise(JSON.valueToTree(in), UUID.randomUUID()));
        Bank bad = new Bank("t", "Keys", null, "EXAM", null, List.of(q(null, "Q?", List.of("A", "B", "C", "D"), 4)));
        assertThrows(ApiException.class, () -> new McqRules().normalise(JSON.valueToTree(bad), UUID.randomUUID()));
        UUID notes = UUID.randomUUID();
        JsonNode ok = new McqRules().normalise(JSON.valueToTree(new Bank("t", "Keys", UUID.randomUUID(), "EXAM", null,
                List.of(q(null, "Q?", List.of("A", "B", "C", "D"), 2)))), notes);
        assertEquals(notes.toString(), ok.path("notesVersionId").asText(), "the server decides which notes a bank came from");
        assertTrue(ok.at("/questions/0/id").asText().startsWith("q"));
        assertEquals("UNVERIFIED", ok.at("/questions/0/verification").asText());
    }

    // ---- questions from the AI ---------------------------------------------------------------------

    @Test
    void generatedQuestionsAreKeptOnlyIfTheyPassEveryRule() {
        JsonNode reply = json("""
                {"questions":[
                  {"style":"application","difficulty":"intermediate","stem":"Which key links two tables?","options":["Foreign key","Primary key","Super key","Candidate key"],"answer":"A","explanation":"A foreign key references another table."},
                  {"stem":"Three options only","options":["a","b","c"],"answer":"A","explanation":"x"},
                  {"stem":"Repeat option?","options":["Yes","yes","No","Maybe"],"answer":"C","explanation":"x"},
                  {"stem":"Which is DDL?","options":["CREATE","SELECT","All of the above","UPDATE"],"answer":1,"explanation":"x"},
                  {"stem":"Which key links two tables","options":["Foreign","Primary","Super","Candidate"],"answer":"A) Foreign","explanation":"x"},
                  {"stem":"Bad key","options":["1","2","3","4"],"answer":"E","explanation":"x"},
                  {"stem":"What is 2+2?","options":["3","4","5","6"],"answer":"2","explanation":"Arithmetic."}]}""");
        Question existing = q("old", "What does SQL stand for?", List.of("a", "b", "c", "d"), 0);
        MaterialsAi.Drafted d = MaterialsAi.readQuestions(reply, List.of(existing), false, "Exam notes, version 2");
        assertEquals(List.of("Which key links two tables?", "What is 2+2?"), d.accepted().stream().map(Question::stem).toList());
        assertEquals("APPLICATION", d.accepted().get(0).style());
        assertEquals(Integer.valueOf(0), d.accepted().get(0).answer());
        assertEquals(Integer.valueOf(1), d.accepted().get(1).answer(), "\"2\" is the second option");
        assertEquals("Exam notes, version 2", d.accepted().get(1).source().label());
        assertEquals(5, d.dropped().size(), d.dropped().toString());
        assertTrue(d.dropped().get(3).contains("repeats"), d.dropped().toString());
        assertNotEquals(d.accepted().get(0).id(), d.accepted().get(1).id());
    }

    @Test
    void answerLettersNumbersAndIndexes() {
        assertEquals(Integer.valueOf(1), MaterialsAi.answer(json("\"b\"")));
        assertEquals(Integer.valueOf(3), MaterialsAi.answer(json("\"D. Super key\"")));
        assertEquals(Integer.valueOf(2), MaterialsAi.answer(json("2")));
        assertEquals(Integer.valueOf(0), MaterialsAi.answer(json("\"1\"")));
        assertEquals(null, MaterialsAi.answer(json("\"Delta\"")));
        assertEquals(null, MaterialsAi.answer(json("7")));
    }

    // ---- quizzes -----------------------------------------------------------------------------------

    static List<QuizService.Pool> pools() {
        List<Question> qs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            qs.add(new Question("q" + i, i % 2 == 0 ? "CODE" : "EXAM", i < 6 ? "BEGINNER" : "ADVANCED", "Question " + i + " about keys and rows?",
                    null, List.of("Right " + i, "Wrong a" + i, "Wrong b" + i, "Wrong c" + i), 0, "Explained.", null, null, null, "UNVERIFIED"));
        }
        return List.of(new QuizService.Pool(UUID.randomUUID(), "Keys", 3, qs));
    }

    @Test
    void theSameSeedGivesTheSameQuizAndKeysFollowShuffledOptions() {
        var a = QuizService.draw(pools(), 8, List.of(), List.of(), true, 42L);
        var b = QuizService.draw(pools(), 8, List.of(), List.of(), true, 42L);
        var c = QuizService.draw(pools(), 8, List.of(), List.of(), true, 43L);
        assertEquals(a.stream().map(x -> x.questionId() + x.options()).toList(), b.stream().map(x -> x.questionId() + x.options()).toList());
        assertNotEquals(a.stream().map(QuizService.QuizQuestion::questionId).toList(), c.stream().map(QuizService.QuizQuestion::questionId).toList());
        for (var x : a) assertTrue(x.options().get(x.answer()).startsWith("Right"), "the key moves with its option");
        assertEquals(8, new HashSet<>(a.stream().map(QuizService.QuizQuestion::questionId).toList()).size(), "no question twice");
        assertTrue(a.stream().anyMatch(x -> x.answer() != 0), "options were shuffled");
    }

    @Test
    void quizFiltersAndTooFewQuestions() {
        var hard = QuizService.draw(pools(), 3, List.of("ADVANCED"), List.of("CODE"), false, 7L);
        assertTrue(hard.stream().allMatch(x -> x.difficulty().equals("ADVANCED") && x.style().equals("CODE")));
        assertTrue(hard.stream().allMatch(x -> x.answer() == 0), "unshuffled keeps the key");
        assertThrows(ApiException.class, () -> QuizService.draw(pools(), 4, List.of("ADVANCED"), List.of("CODE"), false, 7L));
    }

    @Test
    void fileNamesKeepTamilAndDropTheRest() {
        assertEquals("keys-3nf-exam-v2", ExportService.slug("Keys & 3NF: exam v2!"));
        assertEquals("முதன்மை-விசை", ExportService.slug("முதன்மை விசை"));
        assertEquals("export", ExportService.slug("!!!"));
    }
}
