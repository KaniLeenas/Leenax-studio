package com.leenax.studio.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.notes.Learning.Bank;
import com.leenax.studio.notes.Learning.Notes;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.notes.Learning.Reference;
import com.leenax.studio.notes.MaterialsAi.Passage;
import com.leenax.studio.notes.NoteDoc;
import com.leenax.studio.qa.Qa.Finding;
import com.leenax.studio.qa.Qa.IssueRow;
import com.leenax.studio.qa.Qa.RunRow;
import com.leenax.studio.qa.QaAi.Answer;
import com.leenax.studio.qa.QaRules.Block;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaLogicTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Question q(String id, String stem, List<String> options, Integer answer) {
        return new Question(id, "EXAM", "BEGINNER", stem, null, options, answer, "Because.", null, null, null, "UNVERIFIED");
    }

    static final UUID DOC = UUID.randomUUID(), VER = UUID.randomUUID();
    static final List<Passage> PASSAGES = List.of(new Passage("D1", DOC, VER, "Lecture 3", 2,
            "A relation is in third normal form when every non-key attribute depends only on the key."));

    // ---- severity ----------------------------------------------------------------------------------

    @Test
    void theCriticalKindsAreFixedByTheSpecification() {
        assertEquals("CRITICAL", Qa.severity("WRONG_KEY", "minor"));
        assertEquals("CRITICAL", Qa.severity("FACTUAL_ERROR", null));
        assertEquals("MAJOR", Qa.severity("GRAMMAR", "critical"));
        assertEquals("MINOR", Qa.severity("GRAMMAR", "Minor"));
        assertEquals("OTHER", Qa.type("something new"));
        assertEquals("UNSUPPORTED_CLAIM", Qa.type("unsupported claim"));
        assertEquals("UNVERIFIED", Qa.basis("made up"));
        for (String t : Qa.TYPES.keySet()) assertTrue(Qa.CATEGORIES.contains(Qa.TYPES.get(t)), t);
    }

    // ---- the planted wrong key ---------------------------------------------------------------------

    @Test
    void aPlantedWrongKeyIsCriticalWithTheCheckersAnswerProposed() {
        Question planted = q("q1", "2 + 2 = ?", List.of("3", "4", "5", "6"), 2);
        Map<Integer, Answer> answers = QaAi.readAnswers(json("{'answers':[{'number':1,'answer':'B','confidence':'high','ambiguous':false,"
                + "'why':'Two plus two is four.','basis':'AI'}]}"), PASSAGES);
        Finding f = QaAi.keyFinding(planted, 1, answers.get(1));
        assertNotNull(f);
        assertEquals("WRONG_KEY", f.type());
        assertEquals("CRITICAL", f.severity());
        assertEquals("MCQ_KEYS", f.category());
        assertEquals(1, f.proposal().path("answer").asInt());
        assertTrue(f.explanation().startsWith("The key is C, but the checker's answer is B"), f.explanation());

        Question right = q("q2", "2 + 2 = ?", List.of("3", "4", "5", "6"), 1);
        assertNull(QaAi.keyFinding(right, 1, answers.get(1)));
    }

    @Test
    void twoDefensibleOptionsMakeAQuestionAmbiguous() {
        Question qq = q("q1", "Which is a key?", List.of("Primary key", "Candidate key", "Table", "Row"), 0);
        Answer a = QaAi.readAnswers(json("{'answers':[{'number':1,'answer':'B','alsoDefensible':['A'],'why':'Both are keys.'}]}"), PASSAGES).get(1);
        Finding f = QaAi.keyFinding(qq, 1, a);
        assertEquals("AMBIGUOUS", f.type());
        assertEquals("CRITICAL", f.severity());
        assertNull(f.proposal());
    }

    @Test
    void lettersAreReadStrictly() {
        assertEquals(0, QaAi.letter("A"));
        assertEquals(3, QaAi.letter("d)"));
        assertNull(QaAi.letter("Both"));
        assertNull(QaAi.letter("E"));
    }

    // ---- evidence ----------------------------------------------------------------------------------

    @Test
    void citedPagesAreCheckedAgainstTheStoredText() {
        Qa.Evidence found = QaAi.evidence(json("{'basis':'UPLOADED','doc':'D1','page':2,'quote':'every non-key attribute depends only on the key'}"), PASSAGES);
        assertEquals("UPLOADED", found.basis());
        assertTrue(found.found());
        assertEquals(2, found.page());
        Qa.Evidence wrong = QaAi.evidence(json("{'basis':'UPLOADED','doc':'D1','page':2,'quote':'transitive closure of attributes'}"), PASSAGES);
        assertFalse(wrong.found());
        Qa.Evidence missing = QaAi.evidence(json("{'basis':'UPLOADED','doc':'D7','page':9,'quote':'x'}"), PASSAGES);
        assertEquals("UNVERIFIED", missing.basis());
        assertEquals("UNVERIFIED", QaAi.evidence(json("{'basis':'EXTERNAL'}"), PASSAGES).basis());
        Qa.Evidence link = QaAi.evidence(json("{'basis':'EXTERNAL','url':'https://example.org/3nf'}"), PASSAGES);
        assertEquals("EXTERNAL", link.basis());
        assertTrue(link.note().contains("not opened"));
    }

    // ---- notes -------------------------------------------------------------------------------------

    static JsonNode notesDoc() {
        return json("{'type':'doc','content':["
                + "{'type':'heading','attrs':{'level':2},'content':[{'type':'text','text':'Third normal form'}]},"
                + "{'type':'paragraph','content':[{'type':'text','text':'In 3NF every attribute depends on '},"
                + "{'type':'text','text':'any key','marks':[{'type':'bold'}]},{'type':'text','text':'. Transitive dependencies are allowed.'}]},"
                + "{'type':'paragraph','content':[{'type':'text','text':'Keys identify rows. Keys identify rows.'}]}]}");
    }

    @Test
    void notesAreNumberedInBlocksUnderTheirHeadings() {
        List<Block> blocks = QaRules.blocks(notesDoc());
        assertEquals(3, blocks.size());
        assertEquals("B2", blocks.get(1).ref());
        assertEquals("Third normal form", blocks.get(1).heading());
        String label = QaRules.label(blocks, "B2", "Transitive dependencies are allowed.");
        assertEquals("Third normal form: Transitive dependencies are allowed.", label);
    }

    @Test
    void aiFindingsOnNotesAreReadWithTheirCorrection() {
        List<Block> blocks = QaRules.blocks(notesDoc());
        QaAi.NotesCheck c = QaAi.readNotes(json("{'claims':[{'block':'B2','claim':'3NF allows transitive dependencies','verdict':'CONTRADICTED',"
                + "'basis':'UPLOADED','doc':'D1','page':2,'quote':'every non-key attribute depends only on the key'}],"
                + "'issues':[{'block':'b2','type':'factual error','severity':'minor','original':'Transitive dependencies are allowed.',"
                + "'explanation':'3NF removes transitive dependencies.','basis':'UPLOADED','doc':'D1','page':2,"
                + "'quote':'depends only on the key','find':'are allowed','replace':'are not allowed'},"
                + "{'block':'B9','type':'GRAMMAR','explanation':''}]}"), blocks, PASSAGES);
        assertEquals(1, c.claims().size());
        assertEquals("CONTRADICTED", c.claims().get(0).verdict());
        assertEquals(1, c.findings().size(), "an issue without an explanation is dropped");
        Finding f = c.findings().get(0);
        assertEquals("FACTUAL_ERROR", f.type());
        assertEquals("CRITICAL", f.severity(), "a factual error can't be filed as minor");
        assertEquals("B2", f.itemRef());
        assertTrue(f.evidence().get(0).found());
        assertEquals("are not allowed", f.proposal().path("replace").asText());
    }

    @Test
    void anAcceptedNotesCorrectionIsExactOrRefused() {
        JsonNode doc = notesDoc();
        JsonNode fixed = QaRules.applyToNotes(doc, "are allowed", "are not allowed");
        assertTrue(NoteDoc.plainText(fixed).contains("Transitive dependencies are not allowed."));
        assertFalse(NoteDoc.plainText(doc).contains("not allowed"), "the original is untouched");
        ApiException twice = assertThrows(ApiException.class, () -> QaRules.applyToNotes(doc, "Keys identify rows.", "x"));
        assertTrue(twice.getMessage().contains("2 times"));
        ApiException across = assertThrows(ApiException.class, () -> QaRules.applyToNotes(doc, "depends on any key", "depends on the key"));
        assertTrue(across.getMessage().contains("formatting"));
        assertThrows(ApiException.class, () -> QaRules.applyToNotes(doc, "not there", "x"));
    }

    @Test
    void rulesFlagMissingSubtopicsAndReferencesNotFound() {
        Notes n = new Notes("t1", "Normalisation", "BEGINNER", null, notesDoc(),
                List.of(new Reference("Lecture 3 p.2", DOC, VER, 2, null, true), new Reference("Handbook p.9", DOC, VER, 9, null, false)), "gpt-x");
        List<Finding> f = QaRules.notesFindings(n, List.of("Third normal form", "Boyce-Codd normal form"));
        assertEquals(2, f.size());
        assertEquals("BAD_REFERENCE", f.get(0).type());
        assertEquals("COVERAGE_GAP", f.get(1).type());
        assertEquals("MAJOR", f.get(1).severity());
        assertTrue(f.get(1).explanation().contains("Boyce-Codd"));
        assertEquals("subtopic missing", QaRules.ruleOf(JSON.valueToTree(f.get(1).evidence())));
        assertTrue(QaRules.covered("முதன்மை விசை", "முதன்மை விசைகள் ஒவ்வொரு வரிசையையும் அடையாளம் காட்டுகின்றன"));
    }

    // ---- question banks ----------------------------------------------------------------------------

    @Test
    void ruleFindingsOnABank() {
        Bank b = new Bank("t1", "Keys", null, "EXAM", false, List.of(
                q("a", "Which key links two tables?", List.of("Foreign key", "Primary key", "Super key", "Candidate key"), null),
                q("b", "Which normal form removes transitive dependencies?", List.of("2NF", "3NF", "3nf", "1NF"), 1),
                q("c", "Which key links two tables together?", List.of("Foreign key", "Primary key", "Super key", "Candidate key"), 0)));
        List<Finding> f = QaRules.bankFindings(b, List.of());
        assertTrue(f.stream().anyMatch(x -> x.itemRef().equals("a") && x.type().equals("WRONG_KEY") && x.severity().equals("CRITICAL")));
        assertTrue(f.stream().anyMatch(x -> x.itemRef().equals("b") && x.type().equals("AMBIGUOUS") && x.severity().equals("CRITICAL")));
        assertTrue(f.stream().anyMatch(x -> x.itemRef().equals("c") && x.type().equals("DUPLICATE")));

        // once the key is marked, the rule no longer finds it
        Bank fixed = QaRules.applyToBank(b, json("{'questionId':'a','answer':0}"));
        assertEquals(0, fixed.questions().get(0).answer());
        assertTrue(QaRules.ruleFixed("a", "WRONG_KEY", "no correct answer marked", QaRules.bankFindings(fixed, List.of())));
        assertFalse(QaRules.ruleFixed("b", "AMBIGUOUS", "options repeat", QaRules.bankFindings(fixed, List.of())));
        assertThrows(ApiException.class, () -> QaRules.applyToBank(b, json("{'questionId':'a','answer':7}")));
        assertThrows(ApiException.class, () -> QaRules.applyToBank(b, json("{'questionId':'zz','answer':1}")));
    }

    @Test
    void anOpenIssueIsNotRecordedTwice() {
        Finding f = new Finding("q1", "Question 1", "WRONG_KEY", "CRITICAL", "2 + 2 = ?\nKey: C) 5", "x", "AI", List.of(), null, null, List.of());
        IssueRow open = issue("WRONG_KEY", "CRITICAL", "OPEN", "q1", "2 + 2 = ?\nKey: C) 5");
        assertTrue(QaService.same(open, f));
        assertFalse(QaService.same(issue("AMBIGUOUS", "CRITICAL", "OPEN", "q1", "2 + 2 = ?\nKey: C) 5"), f));
        assertEquals(1, QaService.dedupe(List.of(f, f)).size());
    }

    // ---- reports -----------------------------------------------------------------------------------

    static IssueRow issue(String type, String severity, String status, String ref, String original) {
        return new IssueRow(UUID.randomUUID(), 1, null, UUID.randomUUID(), UUID.randomUUID(), 1, ref, "Question 1", Qa.TYPES.get(type), type,
                severity, original, "explained", "AI", JSON.createArrayNode(), null, JSON.createArrayNode(), status, null, null, null, null,
                Instant.now(), null);
    }

    static QaReport.Source source(UUID resource, String kind, List<IssueRow> issues, boolean checked) {
        UUID version = UUID.randomUUID(), run = UUID.randomUUID();
        List<IssueRow> mine = issues.stream().map(i -> new IssueRow(i.id(), i.number(), resource, i.runId(), i.foundInVersionId(), 1,
                i.itemRef(), i.itemLabel(), i.category(), i.type(), i.severity(), i.original(), i.explanation(), i.basis(), i.evidence(),
                i.suggestion(), i.confirmations(), i.status(), i.statusNote(), i.recheck(), null, null, i.createdAt(), null)).toList();
        JsonNode result = json("{'categories':{'MCQ_KEYS':{'checked':20},'ACCURACY':{'checked':20},'EXPLANATIONS':{'checked':20},"
                + "'DIFFICULTY':{'checked':20},'DUPLICATES':{'checked':20}},'claims':[{'basis':'UPLOADED'},{'basis':'AI'}]}");
        List<RunRow> runs = checked ? List.of(new RunRow(run, resource, version, 1, "hash", "CHECK", "claude-x", "Anthropic", null, "gpt-x", true,
                result, "Kani", Instant.now())) : List.of();
        return new QaReport.Source(resource, kind, null, kind.equals("MCQ") ? "Question bank" : "Exam-focused notes", version, 1, "IN_REVIEW",
                "hash", kind.equals("NOTES") ? "Third normal form" : null, runs, Map.of(run, "hash"), mine, List.of(), null, 20);
    }

    @Test
    void aHighScoreNeverHidesACriticalIssue() {
        UUID bank = UUID.randomUUID();
        QaReport.Report r = QaReport.build("t1", "Keys", "now", List.of(source(bank, "MCQ",
                List.of(issue("WRONG_KEY", "CRITICAL", "OPEN", "q1", "x")), true)), List.of());
        QaReport.Part mcq = r.parts().get("MCQ");
        assertEquals(100, mcq.checked());
        assertEquals(1, mcq.failed());
        assertEquals(99, mcq.score());
        assertEquals("BLOCKED", mcq.headline());
        assertEquals("BLOCKED", r.headline());
        assertTrue(r.recommendations().get(0).contains("critical"));
    }

    @Test
    void headlinesFollowTheRule() {
        UUID a = UUID.randomUUID();
        assertEquals("PASSED", QaReport.build("t1", "Keys", "now", List.of(source(a, "MCQ",
                List.of(issue("WRONG_KEY", "CRITICAL", "RESOLVED", "q1", "x"), issue("GRAMMAR", "MINOR", "OPEN", "q2", "y")), true)), List.of()).headline());
        assertEquals("NEEDS_ATTENTION", QaReport.build("t1", "Keys", "now", List.of(source(a, "MCQ",
                List.of(issue("WEAK_EXPLANATION", "MAJOR", "OPEN", "q2", "y")), true)), List.of()).headline());
        assertEquals("NOT_CHECKED", QaReport.build("t1", "Keys", "now", List.of(source(a, "MCQ", List.of(), false)), List.of()).headline());
        QaReport.Report cov = QaReport.build("t1", "Keys", "now", List.of(source(a, "NOTES", List.of(), true)), List.of("Third normal form", "BCNF"));
        assertTrue(cov.coverage().get(0).covered());
        assertFalse(cov.coverage().get(1).covered());
        assertTrue(cov.limitations().stream().anyMatch(l -> l.contains("no second model")));
    }

    @Test
    void aReportCanBeApprovedOnlyOverApprovedCheckedWork() {
        UUID bank = UUID.randomUUID();
        QaReport.Report r = QaReport.build("t1", "Keys", "now", List.of(source(bank, "MCQ", List.of(), true)), List.of());
        JsonNode content = JSON.valueToTree(r);
        UUID version = r.items().get(0).versionId();
        QaReportRules rules = new QaReportRules();
        Context none = new Context(UUID.randomUUID(), null, k -> null, k -> null, k -> List.of(), List::of);
        List<Check> c = rules.checks(rules.normalise(content, null), none);
        assertTrue(c.stream().anyMatch(x -> x.code().equals("approved") && !x.ok() && x.blocking()));
        Context ok = new Context(UUID.randomUUID(), null, k -> null, k -> null, k -> k == Kind.MCQ ? List.of(version) : List.of(), List::of);
        assertTrue(rules.checks(content, ok).stream().allMatch(Check::ok));
        assertFalse(rules.editableByHand());
        assertThrows(ApiException.class, () -> rules.normalise(json("{'headline':'PASSED'}"), null));
    }
}
