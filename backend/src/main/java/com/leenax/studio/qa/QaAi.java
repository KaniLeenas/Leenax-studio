package com.leenax.studio.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.notes.MaterialsAi.Passage;
import com.leenax.studio.qa.Qa.Claim;
import com.leenax.studio.qa.Qa.Evidence;
import com.leenax.studio.qa.Qa.Finding;
import com.leenax.studio.qa.QaRules.Block;
import com.leenax.studio.syllabus.SyllabusAnalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Prompts for academic QA and how the answers are read. The checker never sees an answer
 * key before it answers the question itself. Every page it cites is checked against the
 * stored page text; anything else is labelled for what it is: the AI's own explanation,
 * a link it gave (not opened), or unverified.
 */
public final class QaAi {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String[] LETTERS = {"A", "B", "C", "D"};

    private QaAi() {
    }

    static final String BASIS_RULES = """
            For every claim and finding give its basis:
            - "UPLOADED": a course document passage supports it. Give "doc" (D1, D2, ...), "page" and "quote": 3 to 15 words \
            copied exactly from that page.
            - "EXTERNAL": a published source outside the documents. Give "url". Only if you are sure the source exists.
            - "AI": your own subject knowledge, with no source.
            - "UNVERIFIED": you could not check it.
            Never invent quotes, pages or links.""";

    // ---- notes -------------------------------------------------------------------------------------

    public static String notesSystem() {
        return """
                You are an independent academic checker for university study notes. Documents and notes can be in English, Tamil, \
                or both. You did not write these notes; check them, don't rewrite them.

                Check claim by claim: technical and factual accuracy, maths, code, logical consistency, whether the subtopics \
                listed are covered, explanation and example quality, and grammar. Check the notes against the course document \
                passages first, then your own knowledge.

                Issue types: FACTUAL_ERROR (a statement is wrong), CONTRADICTION (the notes contradict a document or themselves), \
                MISSING_TOPIC (a listed subtopic that is core to the topic is missing), UNSUPPORTED_CLAIM, MATH_ERROR, CODE_ERROR, \
                INCONSISTENT, WEAK_EXPLANATION, WEAK_EXAMPLE, BAD_REFERENCE, GRAMMAR, OTHER. Severity for the non-critical types: \
                MAJOR or MINOR. Report real problems only; don't pad the list.

                %s

                For each issue: "block" (B1, B2, ...), "original" (the exact words from the notes, at most 300 characters), \
                "explanation", "suggestion" (what a correct version says). If a short exact replacement fixes it, add "find" \
                (exact words from that block, as short as possible) and "replace".

                Reply with JSON only:
                {"claims": [{"block": "B3", "claim": "...", "verdict": "SUPPORTED" | "CONTRADICTED" | "UNSUPPORTED", "basis": "...", \
                "doc": "D1", "page": 2, "quote": "...", "url": "..."}],
                 "issues": [{"block": "B3", "type": "...", "severity": "MAJOR", "original": "...", "explanation": "...", "basis": "...", \
                "doc": "D1", "page": 2, "quote": "...", "url": "...", "suggestion": "...", "find": "...", "replace": "..."}]}
                List up to 40 of the most important claims.
                """.formatted(BASIS_RULES);
    }

    public static String notesPrompt(String topic, String level, List<String> subtopics, List<Passage> passages, List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(topic).append("\nLevel: ").append(level).append('\n');
        if (!subtopics.isEmpty()) sb.append("Subtopics the approved topic list includes: ").append(String.join("; ", subtopics)).append('\n');
        passages(sb, passages);
        sb.append("\nThe notes, in numbered blocks:\n");
        for (Block b : blocks) sb.append('[').append(b.ref()).append("] ").append(b.text()).append("\n\n");
        return sb.toString();
    }

    public record NotesCheck(List<Claim> claims, List<Finding> findings) {
    }

    public static NotesCheck readNotes(JsonNode reply, List<Block> blocks, List<Passage> passages) {
        List<Claim> claims = new ArrayList<>();
        for (JsonNode c : reply.path("claims")) {
            String text = c.path("claim").asText("").strip();
            if (text.isEmpty()) continue;
            String verdict = c.path("verdict").asText("").toUpperCase(Locale.ROOT);
            if (!List.of("SUPPORTED", "CONTRADICTED", "UNSUPPORTED").contains(verdict)) verdict = "UNSUPPORTED";
            Evidence e = evidence(c, passages);
            claims.add(new Claim(ref(c.path("block").asText(""), blocks), QaRules.clip(text, 500), verdict, e.basis(), List.of(e)));
            if (claims.size() >= 60) break;
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode i : reply.path("issues")) {
            String explanation = i.path("explanation").asText("").strip();
            if (explanation.isEmpty()) continue;
            String type = Qa.type(i.path("type").asText());
            String block = ref(i.path("block").asText(""), blocks);
            String original = QaRules.clip(i.path("original").asText(null), 4000);
            Evidence e = evidence(i, passages);
            JsonNode proposal = null;
            String find = i.path("find").asText(""), replace = i.path("replace").asText(null);
            if (!find.isBlank() && replace != null && !find.equals(replace)) {
                proposal = JSON.createObjectNode().put("find", find).put("replace", replace);
            }
            findings.add(new Finding(block, QaRules.label(blocks, block, original), type, Qa.severity(type, i.path("severity").asText()),
                    original, QaRules.clip(explanation, 4000), e.basis(), List.of(e), QaRules.clip(i.path("suggestion").asText(null), 4000),
                    proposal, List.of()));
            if (findings.size() >= 40) break;
        }
        return new NotesCheck(claims, findings);
    }

    // ---- questions: answering blind ----------------------------------------------------------------

    public static String answerSystem() {
        return """
                You are an independent examiner. Answer each multiple-choice question yourself, from the course document passages \
                and your own knowledge. The intended answers are not shown to you.

                For each question: "answer" (A, B, C or D: the single best answer), "confidence" (high, medium or low), \
                "ambiguous" (true when more than one option can be defended, or none is right, or the wording allows two readings), \
                "alsoDefensible" (other letters that could be argued), "why" (one or two sentences), "difficulty" (BEGINNER, \
                INTERMEDIATE or ADVANCED, as you judge it), and a basis as below.

                %s

                Reply with JSON only:
                {"answers": [{"number": 1, "answer": "B", "confidence": "high", "ambiguous": false, "alsoDefensible": [], \
                "why": "...", "difficulty": "...", "basis": "...", "doc": "D1", "page": 2, "quote": "...", "url": "..."}]}
                """.formatted(BASIS_RULES);
    }

    public static String answerPrompt(String topic, String notesText, List<Passage> passages, List<Question> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(topic).append('\n');
        passages(sb, passages);
        if (notesText != null && !notesText.isBlank()) sb.append("\nThe approved study notes for this topic:\n").append(notesText).append('\n');
        sb.append("\nQuestions:\n");
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            sb.append(i + 1).append(". ").append(q.stem()).append('\n');
            if (q.code() != null && q.code().text() != null) sb.append("```\n").append(q.code().text()).append("\n```\n");
            List<String> o = q.options() == null ? List.of() : q.options();
            for (int k = 0; k < o.size() && k < 4; k++) sb.append("   ").append(LETTERS[k]).append(") ").append(o.get(k)).append('\n');
        }
        return sb.toString();
    }

    /** The checker's answer to one question (index 0..3, or null when it gave none). */
    public record Answer(int number, Integer answer, String confidence, boolean ambiguous, List<Integer> alsoDefensible, String why,
                         String difficulty, Evidence evidence) {
    }

    public static Map<Integer, Answer> readAnswers(JsonNode reply, List<Passage> passages) {
        Map<Integer, Answer> out = new java.util.LinkedHashMap<>();
        for (JsonNode a : reply.path("answers")) {
            int n = a.path("number").asInt(-1);
            if (n < 1) continue;
            List<Integer> also = new ArrayList<>();
            a.path("alsoDefensible").forEach(x -> {
                Integer k = letter(x.asText());
                if (k != null) also.add(k);
            });
            String d = a.path("difficulty").asText("").toUpperCase(Locale.ROOT);
            out.put(n, new Answer(n, letter(a.path("answer").asText()), a.path("confidence").asText("medium").toLowerCase(Locale.ROOT),
                    a.path("ambiguous").asBoolean(false), also, QaRules.clip(a.path("why").asText(""), 1000),
                    List.of("BEGINNER", "INTERMEDIATE", "ADVANCED").contains(d) ? d : null, evidence(a, passages)));
        }
        return out;
    }

    /**
     * What a blind answer says about a question's key: a wrong key (critical, with the checker's
     * answer as a proposed correction), an ambiguous question (critical), or nothing.
     */
    public static Finding keyFinding(Question q, int number, Answer a) {
        if (a == null || a.answer() == null || q.answer() == null || q.answer() < 0 || q.answer() > 3 || q.options() == null
                || q.options().size() != 4) return null;
        String where = "Question " + number;
        if (a.ambiguous() || (a.alsoDefensible().contains(q.answer()) && !a.answer().equals(q.answer()))) {
            return new Finding(q.id(), where, "AMBIGUOUS", "CRITICAL", q.stem(),
                    "More than one option can be defended" + (a.why().isEmpty() ? "." : ": " + a.why()), a.evidence().basis(),
                    List.of(a.evidence()), "Reword the question or the options so exactly one is right.", null, List.of());
        }
        if (!a.answer().equals(q.answer())) {
            String expl = "The key is " + LETTERS[q.answer()] + ", but the checker's answer is " + LETTERS[a.answer()]
                    + (a.confidence().equals("low") ? " (with low confidence)" : "") + (a.why().isEmpty() ? "." : ": " + a.why());
            ObjectNode proposal = JSON.createObjectNode().put("questionId", q.id()).put("answer", a.answer());
            return new Finding(q.id(), where, "WRONG_KEY", "CRITICAL", q.stem() + "\nKey: " + LETTERS[q.answer()] + ") " + q.options().get(q.answer()),
                    QaRules.clip(expl, 4000), a.evidence().basis(), List.of(a.evidence()),
                    "Mark " + LETTERS[a.answer()] + ") " + q.options().get(a.answer()) + " as the answer, if that's right.", proposal, List.of());
        }
        return null;
    }

    // ---- questions: reviewing with the keys --------------------------------------------------------

    public static String reviewSystem() {
        return """
                You are an independent academic checker for multiple-choice questions. The keys and explanations are shown. \
                Check: factual errors in a question or its explanation (FACTUAL_ERROR), a misleading or wrong explanation \
                (WEAK_EXPLANATION), maths or code errors (MATH_ERROR, CODE_ERROR), a difficulty label that's clearly wrong \
                (WRONG_DIFFICULTY), and grammar (GRAMMAR). Severity for the non-critical types: MAJOR or MINOR. Don't report the \
                choice of key here: that is checked separately. Report real problems only.

                %s

                For each issue: "number" (the question), "type", "severity", "explanation", "suggestion". If a fix is a new stem \
                or explanation, give it as "stem" or "explanationFix".

                Reply with JSON only:
                {"issues": [{"number": 2, "type": "...", "severity": "...", "explanation": "...", "basis": "...", "doc": "D1", \
                "page": 2, "quote": "...", "url": "...", "suggestion": "...", "stem": "...", "explanationFix": "..."}]}
                """.formatted(BASIS_RULES);
    }

    public static String reviewPrompt(String topic, List<Passage> passages, List<Question> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(topic).append('\n');
        passages(sb, passages);
        sb.append("\nQuestions with their keys:\n");
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            sb.append(i + 1).append(". [").append(q.difficulty() == null ? "no difficulty" : q.difficulty()).append("] ").append(q.stem()).append('\n');
            if (q.code() != null && q.code().text() != null) sb.append("```\n").append(q.code().text()).append("\n```\n");
            List<String> o = q.options() == null ? List.of() : q.options();
            for (int k = 0; k < o.size() && k < 4; k++) sb.append("   ").append(LETTERS[k]).append(") ").append(o.get(k)).append('\n');
            if (q.answer() != null && q.answer() >= 0 && q.answer() < 4) sb.append("   Key: ").append(LETTERS[q.answer()]).append('\n');
            if (q.explanation() != null) sb.append("   Explanation: ").append(q.explanation()).append('\n');
        }
        return sb.toString();
    }

    public static List<Finding> readReview(JsonNode reply, List<Question> questions, List<Passage> passages) {
        List<Finding> out = new ArrayList<>();
        for (JsonNode i : reply.path("issues")) {
            int n = i.path("number").asInt(-1);
            String explanation = i.path("explanation").asText("").strip();
            if (n < 1 || n > questions.size() || explanation.isEmpty()) continue;
            String type = Qa.type(i.path("type").asText());
            if (type.equals("WRONG_KEY") || type.equals("AMBIGUOUS")) continue; // decided by the blind answers
            Question q = questions.get(n - 1);
            Evidence e = evidence(i, passages);
            ObjectNode proposal = null;
            if (i.path("stem").isTextual() && !i.path("stem").asText().isBlank()) {
                proposal = JSON.createObjectNode().put("questionId", q.id()).put("stem", i.path("stem").asText());
            }
            if (i.path("explanationFix").isTextual() && !i.path("explanationFix").asText().isBlank()) {
                if (proposal == null) proposal = JSON.createObjectNode().put("questionId", q.id());
                proposal.put("explanation", i.path("explanationFix").asText());
            }
            out.add(new Finding(q.id(), "Question " + n, type, Qa.severity(type, i.path("severity").asText()), q.stem(),
                    QaRules.clip(explanation, 4000), e.basis(), List.of(e), QaRules.clip(i.path("suggestion").asText(null), 4000), proposal,
                    List.of()));
            if (out.size() >= 60) break;
        }
        return out;
    }

    // ---- a second model's view, and rechecks -------------------------------------------------------

    public static String confirmSystem() {
        return """
                You are a second, independent academic checker. Another checker reported the issues below. For each, say whether \
                you agree it is a real problem, using the passages and your own knowledge.
                Reply with JSON only: {"verdicts": [{"issue": 1, "agrees": true, "note": "one sentence"}]}
                """;
    }

    public static String recheckSystem() {
        return """
                You are an academic checker. Each issue below was found in an earlier version. Look at the current text and say \
                whether the issue is fixed. Fixed means the problem is gone, not just reworded.
                Reply with JSON only: {"verdicts": [{"issue": 1, "fixed": true, "note": "one sentence"}]}
                """;
    }

    /** The issues, each with the current text of where it was found. */
    public static String issuesPrompt(String topic, List<Passage> passages, List<String[]> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(topic).append('\n');
        passages(sb, passages);
        sb.append("\nIssues:\n");
        for (int i = 0; i < issues.size(); i++) {
            String[] x = issues.get(i);
            sb.append("Issue ").append(i + 1).append(" (").append(x[0]).append("): ").append(x[1]).append('\n');
            if (x[2] != null) sb.append("   Found in: ").append(x[2]).append('\n');
            sb.append("   Current text: ").append(x[3] == null ? "(no longer present)" : x[3]).append("\n\n");
        }
        return sb.toString();
    }

    public record Verdict(boolean yes, String note) {
    }

    public static Map<Integer, Verdict> readVerdicts(JsonNode reply, String field) {
        Map<Integer, Verdict> out = new java.util.HashMap<>();
        for (JsonNode v : reply.path("verdicts")) {
            int n = v.path("issue").asInt(-1);
            if (n >= 1 && v.has(field)) out.put(n, new Verdict(v.path(field).asBoolean(false), QaRules.clip(v.path("note").asText(""), 500)));
        }
        return out;
    }

    // ---- shared ------------------------------------------------------------------------------------

    private static void passages(StringBuilder sb, List<Passage> passages) {
        if (passages.isEmpty()) {
            sb.append("\nNo course document passages were found for this topic: rely on your own knowledge and say so (basis AI).\n");
            return;
        }
        sb.append("\nCourse document passages:\n");
        for (Passage p : passages) {
            sb.append("--- ").append(p.label()).append(" “").append(p.title()).append("”, page ").append(p.page()).append(" ---\n")
                    .append(p.text()).append('\n');
        }
    }

    /** The basis of a claim or finding, with a cited page's quote checked against that page. */
    static Evidence evidence(JsonNode x, List<Passage> passages) {
        String basis = Qa.basis(x.path("basis").asText());
        String quote = QaRules.clip(x.path("quote").asText(null), 300);
        if (basis.equals("UPLOADED")) {
            String doc = x.path("doc").asText("");
            int page = x.path("page").asInt(-1);
            Passage p = passages.stream().filter(s -> s.label().equalsIgnoreCase(doc) && s.page() == page).findFirst()
                    .orElseGet(() -> passages.stream().filter(s -> s.label().equalsIgnoreCase(doc)).findFirst().orElse(null));
            if (p == null) {
                return new Evidence("UNVERIFIED", null, null, null, null, quote, false, null, "Cited a page that wasn't given to the checker");
            }
            boolean found = quote != null && SyllabusAnalysis.contains(p.text(), quote);
            return new Evidence("UPLOADED", p.title() + " p." + p.page(), p.documentId(), p.versionId(), p.page(), quote, found,
                    null, found ? null : "The quoted words weren't found on that page");
        }
        if (basis.equals("EXTERNAL")) {
            String url = x.path("url").asText("");
            if (!url.matches("(?i)https?://\\S{3,1500}")) return new Evidence("UNVERIFIED", null, null, null, null, quote, null, null, "No link given");
            return new Evidence("EXTERNAL", null, null, null, null, quote, null, url, "Link given by the AI; not opened or checked");
        }
        return new Evidence(basis, null, null, null, null, quote, null, null, null);
    }

    static String ref(String block, List<Block> blocks) {
        String b = block.strip().toUpperCase(Locale.ROOT);
        return blocks.stream().anyMatch(x -> x.ref().equals(b)) ? b : null;
    }

    static Integer letter(String s) {
        if (s == null) return null;
        String t = s.strip().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        int k = "ABCD".indexOf(t.charAt(0));
        return k >= 0 && (t.length() == 1 || !Character.isLetter(t.charAt(1))) ? k : null;
    }
}
