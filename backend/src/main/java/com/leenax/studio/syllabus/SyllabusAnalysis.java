package com.leenax.studio.syllabus;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.syllabus.Content.Analysis;
import com.leenax.studio.syllabus.Content.Assessment;
import com.leenax.studio.syllabus.Content.Contradiction;
import com.leenax.studio.syllabus.Content.DocumentRef;
import com.leenax.studio.syllabus.Content.Node;
import com.leenax.studio.syllabus.Content.Outcome;
import com.leenax.studio.syllabus.Content.Source;
import com.leenax.studio.syllabus.Content.Syllabus;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The syllabus report: the prompt sent to your AI account, and how its answer is checked.
 * The model sees the chosen documents page by page, labelled D1, D2, ..., and must cite a
 * page and copy a few words from it for every item. Each citation is then checked against
 * the stored page text: citations to pages that don't exist are removed, and the others
 * are marked found or not found, so the reviewer sees which items are backed by the text.
 */
public final class SyllabusAnalysis {

    private SyllabusAnalysis() {
    }

    /** A document version given to the analysis, with its pages (page number → text). */
    public record SourceDoc(String label, UUID documentId, UUID versionId, String title, int versionNo, Map<Integer, String> pages) {
    }

    static final String SYSTEM = """
            You analyse university course documents (module descriptors, handbooks, lecture notes, past papers) \
            and report the syllabus they describe. Documents can be in English, Tamil, or both.

            Rules:
            - Use only the documents given. Never add topics, outcomes or assessments that aren't in them.
            - Every item cites where it comes from: {"doc": "D1", "page": 3, "quote": "..."}. The quote is 3 to 15 words \
            copied exactly from that page, in the page's own language and spelling.
            - Keep titles in the language the documents use. Don't translate.
            - structure is at most three levels: chapters (or units), their topics, and subtopics.
            - contradictions: places where two documents, or two parts of one document, disagree (different assessment \
            weights, a topic listed in one but not the other, different hours or codes). Cite both sides. Leave the list \
            empty if there are none.
            - assessments.kind is one of EXAM, COURSEWORK, QUIZ, PRACTICAL, PRESENTATION, PROJECT, OTHER. weight is a \
            percentage, only if a document states it.

            Reply with JSON only, in this shape:
            {"summary": "two or three sentences",
             "structure": [{"title": "...", "sources": [...], "children": [{"title": "...", "sources": [...], "children": [...]}]}],
             "outcomes": [{"code": "LO1", "text": "...", "sources": [...]}],
             "assessments": [{"title": "...", "kind": "EXAM", "weight": 60, "details": "...", "sources": [...]}],
             "contradictions": [{"description": "...", "sources": [...]}]}
            """;

    public static String prompt(List<SourceDoc> docs, String guidance) {
        StringBuilder sb = new StringBuilder();
        if (guidance != null && !guidance.isBlank()) {
            sb.append("Guidance from the lecturer (follow it unless it conflicts with the rules):\n").append(guidance.strip()).append("\n\n");
        }
        sb.append("Documents:\n");
        for (SourceDoc d : docs) {
            sb.append("\n<document id=\"").append(d.label()).append("\" title=\"").append(attr(d.title())).append("\">\n");
            d.pages().forEach((page, text) -> sb.append("<page n=\"").append(page).append("\">\n").append(text == null ? "" : text.strip())
                    .append("\n</page>\n"));
            sb.append("</document>\n");
        }
        return sb.toString();
    }

    public static int sourceChars(List<SourceDoc> docs) {
        return docs.stream().flatMap(d -> d.pages().values().stream()).mapToInt(t -> t == null ? 0 : t.length()).sum();
    }

    // ---- reading the answer ------------------------------------------------------------------------

    /** Counts of citations kept, found on their page, and removed. */
    static final class Tally {
        int kept;
        int found;
        int removed;
    }

    public static Syllabus read(JsonNode reply, List<SourceDoc> docs, String model) {
        Map<String, SourceDoc> byLabel = new HashMap<>();
        docs.forEach(d -> byLabel.put(d.label().toUpperCase(Locale.ROOT), d));
        Tally tally = new Tally();

        List<Node> structure = nodes(reply.path("structure"), 1, byLabel, tally);
        List<Outcome> outcomes = new ArrayList<>();
        for (JsonNode o : reply.path("outcomes")) {
            String text = str(o, "text");
            if (text != null) outcomes.add(new Outcome(null, cut(str(o, "code"), 20), cut(text, Content.TEXT_MAX), cite(o, byLabel, tally)));
        }
        List<Assessment> assessments = new ArrayList<>();
        for (JsonNode a : reply.path("assessments")) {
            String title = str(a, "title");
            if (title == null) continue;
            Double weight = a.path("weight").isNumber() ? a.path("weight").asDouble() : null;
            if (weight != null && (weight < 0 || weight > 100)) weight = null;
            assessments.add(new Assessment(null, cut(title, Content.TITLE_MAX), str(a, "kind"), weight, cut(str(a, "details"), Content.TEXT_MAX),
                    cite(a, byLabel, tally)));
        }
        List<Contradiction> contradictions = new ArrayList<>();
        for (JsonNode c : reply.path("contradictions")) {
            String d = str(c, "description");
            if (d != null) contradictions.add(new Contradiction(null, cut(d, Content.TEXT_MAX), cite(c, byLabel, tally), null));
        }
        List<DocumentRef> refs = docs.stream()
                .map(d -> new DocumentRef(d.documentId(), d.versionId(), d.title(), d.versionNo(), d.label())).toList();
        Analysis analysis = new Analysis(model, Instant.now().toString(), tally.kept, tally.found, tally.removed);
        return SyllabusRules.clean(new Syllabus(cut(str(reply, "summary"), 4000), structure, outcomes, assessments, contradictions, refs, analysis));
    }

    private static List<Node> nodes(JsonNode arr, int depth, Map<String, SourceDoc> docs, Tally tally) {
        List<Node> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode n : arr) {
            String title = str(n, "title");
            if (title == null) continue;
            List<Node> kids = depth >= Content.MAX_DEPTH ? List.of() : nodes(n.path("children"), depth + 1, docs, tally);
            out.add(new Node(null, cut(title, Content.TITLE_MAX), cite(n, docs, tally), kids));
        }
        return out;
    }

    /** The item's citations that point at a real page, each marked found if its quote is on that page. */
    static List<Source> cite(JsonNode item, Map<String, SourceDoc> docs, Tally tally) {
        List<Source> out = new ArrayList<>();
        for (JsonNode s : item.path("sources")) {
            String label = str(s, "doc");
            int page = s.path("page").asInt(-1);
            SourceDoc d = label == null ? null : docs.get(label.toUpperCase(Locale.ROOT));
            if (d == null || !d.pages().containsKey(page)) {
                tally.removed++;
                continue;
            }
            String quote = cut(str(s, "quote"), 500);
            boolean found = quote != null && contains(d.pages().get(page), quote);
            tally.kept++;
            if (found) tally.found++;
            out.add(new Source(d.documentId(), d.versionId(), page, quote, found));
            if (out.size() == 20) break;
        }
        return out;
    }

    /**
     * Whether the quoted words appear on the page, ignoring case, punctuation, spacing and
     * line breaks (OCR and PDFs break lines anywhere). Tamil vowel signs count as letters.
     */
    public static boolean contains(String page, String quote) {
        String q = words(quote);
        if (q.length() < 4) return false;
        return words(page).contains(q);
    }

    static String words(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("[\\u200B-\\u200D\\uFEFF\\u00AD]", "");
        return t.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").strip();
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String attr(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("<", "(").replace(">", ")");
    }
}
