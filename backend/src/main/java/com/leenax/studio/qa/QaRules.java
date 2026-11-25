package com.leenax.studio.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.documents.DocumentText;
import com.leenax.studio.notes.Learning.Bank;
import com.leenax.studio.notes.Learning.Notes;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.notes.Learning.Reference;
import com.leenax.studio.notes.McqRules;
import com.leenax.studio.notes.NoteDoc;
import com.leenax.studio.qa.Qa.Evidence;
import com.leenax.studio.qa.Qa.Finding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The checks that need no AI: references that weren't found on their page, syllabus
 * subtopics the notes never mention, and every question rule. Also how an accepted
 * correction is applied, which is exact or refused, never a guess.
 */
public final class QaRules {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Marks a finding made by a rule here, so a recheck can run the rule again. */
    static final String RULE = "rule:";
    static final double COVERED = 0.75;

    private QaRules() {
    }

    // ---- notes as numbered blocks ------------------------------------------------------------------

    /** A top-level piece of the notes, numbered for the checker, under its nearest heading. */
    public record Block(String ref, String heading, String text) {
    }

    public static List<Block> blocks(JsonNode doc) {
        List<Block> out = new ArrayList<>();
        String heading = null;
        int n = 0;
        for (JsonNode b : doc.path("content")) {
            String text = NoteDoc.plainText(b);
            if (text.isBlank()) continue;
            if ("heading".equals(b.path("type").asText())) heading = text.strip();
            out.add(new Block("B" + (++n), heading, text.strip()));
        }
        return out;
    }

    public static String label(List<Block> blocks, String ref, String original) {
        Block b = blocks.stream().filter(x -> x.ref().equals(ref)).findFirst().orElse(null);
        String where = b == null || b.heading() == null ? null : b.heading();
        String what = original == null || original.isBlank() ? (b == null ? null : b.text()) : original;
        String excerpt = what == null ? "" : (what.length() > 120 ? what.substring(0, 117).strip() + "…" : what.strip());
        String s = where == null ? excerpt : where + (excerpt.isEmpty() ? "" : ": " + excerpt);
        return s.length() > 300 ? s.substring(0, 299) + "…" : s;
    }

    // ---- rule findings -----------------------------------------------------------------------------

    /** Notes: references whose quoted words weren't on the page, and subtopics never mentioned. */
    public static List<Finding> notesFindings(Notes n, List<String> subtopics) {
        List<Finding> out = new ArrayList<>();
        for (Reference r : n.references() == null ? List.<Reference>of() : n.references()) {
            if (Boolean.FALSE.equals(r.found())) {
                out.add(rule("ref:" + r.label(), "Sources: " + r.label(), "BAD_REFERENCE", "MINOR", r.label(),
                        "The words this reference quotes weren't found on that page, so it may not support what the notes say.",
                        "ESTIMATE", "Check the page and correct the reference, or remove it.", "reference not found"));
            }
        }
        String text = NoteDoc.plainText(n.doc());
        for (String s : subtopics) {
            if (!covered(s, text)) {
                out.add(rule("sub:" + s, "Coverage: " + s, "COVERAGE_GAP", "MAJOR", null,
                        "The subtopic “" + s + "” from the approved topic list doesn't seem to be covered: its words don't appear in the notes.",
                        "ESTIMATE", "Add a section on " + s + ", or check it is covered under another name.", "subtopic missing"));
            }
        }
        return out;
    }

    /** Whether most of a subtopic's words appear in the text (any language). */
    public static boolean covered(String subtopic, String text) {
        List<String> words = DocumentText.words(subtopic).stream().filter(w -> w.codePointCount(0, w.length()) > 2).toList();
        if (words.isEmpty()) words = DocumentText.words(subtopic);
        if (words.isEmpty()) return true;
        Set<String> have = new HashSet<>(DocumentText.words(text));
        long found = words.stream().filter(w -> have.contains(w) || have.stream().anyMatch(h -> h.startsWith(w))).count();
        return (double) found / words.size() >= COVERED;
    }

    /** Questions: the rules (an unmarked key or repeated options are critical), repeats in this bank and others. */
    public static List<Finding> bankFindings(Bank b, List<Bank> others) {
        List<Finding> out = new ArrayList<>();
        List<Question> qs = McqRules.list(b.questions());
        boolean allowAllNone = Boolean.TRUE.equals(b.allowAllNone());
        for (int i = 0; i < qs.size(); i++) {
            Question q = qs.get(i);
            String where = "Question " + (i + 1);
            for (String p : McqRules.problems(q, allowAllNone)) {
                String type = switch (p) {
                    case "no correct answer marked" -> "WRONG_KEY";
                    case "options repeat" -> "AMBIGUOUS";
                    default -> "RULE_BREAK";
                };
                out.add(rule(q.id(), where, type, "MAJOR", q.stem(), capital(p) + ".", "ESTIMATE", null, p));
            }
            for (int j = 0; j < i; j++) {
                double s = McqRules.similarity(q.stem(), qs.get(j).stem());
                if (s >= 0.6) {
                    out.add(rule(q.id(), where, "DUPLICATE", s >= 0.85 ? "MAJOR" : "MINOR", q.stem(),
                            (s >= 0.85 ? "The same question as " : "Very like ") + "question " + (j + 1) + ".", "ESTIMATE",
                            "Remove one of them, or make them test different things.", "repeat of " + qs.get(j).id()));
                }
            }
            for (Bank o : others) {
                for (Question x : McqRules.list(o.questions())) {
                    if (McqRules.similarity(q.stem(), x.stem()) >= 0.85) {
                        out.add(rule(q.id(), where, "DUPLICATE", "MINOR", q.stem(), "The same question is in the bank for “" + o.topicTitle() + "”.",
                                "ESTIMATE", null, "repeat of " + o.topicId() + "/" + x.id()));
                    }
                }
            }
        }
        return out;
    }

    private static Finding rule(String ref, String label, String type, String severity, String original, String explanation, String basis,
                                String suggestion, String code) {
        Evidence e = new Evidence(basis, null, null, null, null, null, null, null, RULE + code);
        return new Finding(clip(ref, 80), clip(label, 300), type, Qa.severity(type, severity), clip(original, 4000), explanation, basis,
                List.of(e), suggestion, null, List.of());
    }

    /** The rule a finding came from, or null when the AI found it. */
    public static String ruleOf(JsonNode evidence) {
        for (JsonNode e : evidence) {
            String note = e.path("note").asText("");
            if (note.startsWith(RULE)) return note.substring(RULE.length());
        }
        return null;
    }

    /** Whether a rule finding is gone from new findings (same item, kind and rule). */
    public static boolean ruleFixed(String itemRef, String type, String rule, List<Finding> now) {
        return now.stream().noneMatch(f -> type.equals(f.type()) && java.util.Objects.equals(itemRef, f.itemRef())
                && rule.equals(ruleOf(JSON.valueToTree(f.evidence()))));
    }

    // ---- applying a correction ---------------------------------------------------------------------

    /**
     * Replaces {@code find} with {@code replace} in the notes. The words must sit in one piece of
     * text, exactly once in the whole document; otherwise it's refused so a person makes the change.
     */
    public static JsonNode applyToNotes(JsonNode doc, String find, String replace) {
        if (find == null || find.isBlank() || replace == null) throw unusable("This correction says nothing to change.");
        ObjectNode copy = doc.deepCopy();
        List<ObjectNode> hits = new ArrayList<>();
        int[] total = {0};
        NoteDoc.walk(copy, n -> {
            if ("text".equals(n.path("type").asText())) {
                String t = n.path("text").asText("");
                int c = count(t, find);
                total[0] += c;
                if (c > 0) hits.add((ObjectNode) n);
            }
        });
        if (total[0] == 0) {
            String all = NoteDoc.plainText(doc);
            throw unusable(all.contains(find)
                    ? "The words to change run across formatting (bold, a link, …), so this can't be applied automatically. Make the change by hand."
                    : "The words to change aren't in this version any more. Make the change by hand if it's still needed.");
        }
        if (total[0] > 1) throw unusable("The words to change appear " + total[0] + " times, so it isn't clear which to change. Make the change by hand.");
        ObjectNode n = hits.get(0);
        n.put("text", n.path("text").asText().replace(find, replace));
        if (n.path("text").asText().isEmpty()) {
            // an emptied text node isn't allowed in the editor: drop it
            dropEmpty(copy);
        }
        return copy;
    }

    private static void dropEmpty(JsonNode n) {
        JsonNode content = n.path("content");
        if (!(content instanceof ArrayNode arr)) return;
        for (int i = arr.size() - 1; i >= 0; i--) {
            JsonNode k = arr.get(i);
            if ("text".equals(k.path("type").asText()) && k.path("text").asText().isEmpty()) arr.remove(i);
            else dropEmpty(k);
        }
    }

    private static int count(String s, String find) {
        int c = 0;
        for (int i = s.indexOf(find); i >= 0; i = s.indexOf(find, i + find.length())) c++;
        return c;
    }

    /** Applies a question correction: {questionId, answer?, options?, stem?, explanation?}. */
    public static Bank applyToBank(Bank b, JsonNode p) {
        String id = p.path("questionId").asText("");
        List<Question> qs = new ArrayList<>(McqRules.list(b.questions()));
        int i = -1;
        for (int k = 0; k < qs.size(); k++) if (qs.get(k).id().equals(id)) i = k;
        if (i < 0) throw unusable("That question isn't in this version any more.");
        Question q = qs.get(i);
        Integer answer = q.answer();
        List<String> options = q.options();
        String stem = q.stem(), explanation = q.explanation();
        boolean changed = false;
        if (p.has("answer")) {
            int a = p.path("answer").asInt(-1);
            if (a < 0 || a > 3) throw unusable("The proposed answer isn't one of the four options.");
            answer = a;
            changed = true;
        }
        if (p.path("options").isArray()) {
            List<String> o = new ArrayList<>();
            p.path("options").forEach(x -> o.add(x.asText()));
            if (o.size() != 4) throw unusable("The proposed options aren't four.");
            options = o;
            changed = true;
        }
        if (p.path("stem").isTextual() && !p.path("stem").asText().isBlank()) {
            stem = p.path("stem").asText();
            changed = true;
        }
        if (p.path("explanation").isTextual() && !p.path("explanation").asText().isBlank()) {
            explanation = p.path("explanation").asText();
            changed = true;
        }
        if (!changed) throw unusable("This correction says nothing to change.");
        qs.set(i, new Question(q.id(), q.style(), q.difficulty(), stem, q.code(), options, answer, explanation, q.optionExplanations(),
                q.subtopic(), q.source(), "UNVERIFIED"));
        return new Bank(b.topicId(), b.topicTitle(), b.notesVersionId(), b.notesLevel(), b.allowAllNone(), qs);
    }

    private static ApiException unusable(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
