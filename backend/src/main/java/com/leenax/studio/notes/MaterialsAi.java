package com.leenax.studio.notes;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.notes.Learning.Code;
import com.leenax.studio.notes.Learning.Notes;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.notes.Learning.Reference;
import com.leenax.studio.syllabus.SyllabusAnalysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Prompts for notes and questions, and how the answers are checked. The AI writes; what
 * comes back is cleaned, citations are checked against the stored pages, and questions
 * that break the rules are dropped and counted, never silently fixed.
 */
public final class MaterialsAi {

    private MaterialsAi() {
    }

    /** A passage given to the AI: a document page, labelled D1, D2, ... */
    public record Passage(String label, UUID documentId, UUID versionId, String title, int page, String text) {
    }

    /** Everything the notes prompt needs about the topic. */
    public record TopicBrief(String id, String title, String path, List<String> subtopics, List<String> prerequisites,
                             List<String> outcomes, String difficulty) {
    }

    static final String SECTIONS = """
            Introduction; Technical definition; Detailed explanation; Important terminology; Fundamental concepts; \
            Step-by-step explanation; Practical examples; Real-world applications; Code examples (only where the subject uses code); \
            Mathematical calculations (only where relevant); Diagrams; Flowcharts; Comparison tables; Advantages; Disadvantages; \
            Technical facts; Exam-focused points; Common mistakes; Misconceptions; Memory techniques; Summary; Quick revision notes""";

    static final Map<String, String> LEVEL_GUIDE = Map.of(
            "BEGINNER", "for someone new to the subject: plain words, short paragraphs, every term explained, everyday examples",
            "INTERMEDIATE", "for a student who knows the basics: fuller explanations, worked examples, connections between ideas",
            "ADVANCED", "for a strong student: depth, edge cases, trade-offs and how the ideas extend",
            "UNIVERSITY", "at university level: precise definitions, formal notation where the subject uses it, and rigour",
            "EXAM", "for exam revision: what examiners ask, model answers, mark-scoring points, common mistakes and quick recall");

    // ---- notes -------------------------------------------------------------------------------------

    public static String notesSystem() {
        return """
                You write university study notes from course documents. Documents can be in English, Tamil, or both.

                Rules:
                - Write in your own words. Never copy more than a short phrase from the documents: they are for facts and \
                structure, not wording. Never reproduce exam questions from them.
                - Stay within the topic, at the level asked. Use the documents for facts; where you add general knowledge, keep \
                it standard and uncontroversial.
                - Write in the language the documents use for this subject (English, Tamil, or the mix they use).
                - Use these sections, in this order, leaving out any that don't fit the subject: %s.
                - Blocks: {"type":"paragraph","text":...}, {"type":"subheading","text":...}, {"type":"bullets","items":[...]}, \
                {"type":"numbered","items":[...]}, {"type":"table","header":[...],"rows":[[...]]}, \
                {"type":"code","language":"python","code":...}, {"type":"equation","latex":...}, \
                {"type":"diagram","mermaid":...}, {"type":"quote","text":...}.
                - In text, **bold** key terms, use `code` for identifiers and $...$ for short maths.
                - Diagrams and flowcharts are Mermaid ("flowchart TD" and similar), short and correct.
                - references: the document pages you relied on, with 3 to 15 words copied exactly from each page as "quote".

                Reply with JSON only:
                {"title": "...", "sections": [{"heading": "...", "blocks": [...]}],
                 "references": [{"doc": "D1", "page": 3, "quote": "...", "note": "what it supports"}]}
                """.formatted(SECTIONS);
    }

    public static String notesPrompt(TopicBrief t, String level, List<Passage> passages, String guidance) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(t.title()).append('\n');
        if (t.path() != null) sb.append("Where it sits: ").append(t.path()).append('\n');
        if (!t.subtopics().isEmpty()) sb.append("Subtopics to cover: ").append(String.join("; ", t.subtopics())).append('\n');
        if (!t.prerequisites().isEmpty()) sb.append("Students already know: ").append(String.join("; ", t.prerequisites())).append('\n');
        if (t.difficulty() != null) sb.append("Topic difficulty: ").append(t.difficulty().toLowerCase(Locale.ROOT)).append('\n');
        if (!t.outcomes().isEmpty()) sb.append("Module learning outcomes:\n- ").append(String.join("\n- ", t.outcomes())).append('\n');
        sb.append("Level: ").append(Learning.levelLabel(level)).append(", ").append(LEVEL_GUIDE.getOrDefault(level, "")).append('\n');
        if (guidance != null && !guidance.isBlank()) sb.append("Guidance from the lecturer: ").append(guidance.strip()).append('\n');
        sb.append("\nCourse document pages:\n");
        for (Passage p : passages) {
            sb.append("\n<page doc=\"").append(p.label()).append("\" title=\"").append(attr(p.title())).append("\" n=\"").append(p.page())
                    .append("\">\n").append(p.text().strip()).append("\n</page>\n");
        }
        if (passages.isEmpty()) sb.append("(none found: write from standard knowledge of the topic, and list no references)\n");
        return sb.toString();
    }

    /** The notes from the AI's answer, with each reference checked against its page. */
    public static Notes readNotes(JsonNode reply, TopicBrief t, String level, List<Passage> passages, String model) {
        String title = text(reply, "title");
        JsonNode doc = NoteDoc.clean(NoteDoc.fromSections(title != null ? title : t.title(), reply.path("sections")));
        List<Reference> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode r : reply.path("references")) {
            String label = text(r, "doc");
            int page = r.path("page").asInt(-1);
            Passage p = passages.stream().filter(x -> x.label().equalsIgnoreCase(label == null ? "" : label) && x.page() == page)
                    .findFirst().orElse(null);
            if (p == null || !seen.add(p.label() + ":" + page)) continue;
            String quote = text(r, "quote");
            boolean found = quote != null && SyllabusAnalysis.contains(p.text(), quote);
            refs.add(new Reference(p.title() + ", page " + page, p.documentId(), p.versionId(), page, cut(text(r, "note"), 500), found));
        }
        return new Notes(t.id(), t.title(), level, null, doc, refs, model);
    }

    // ---- questions ---------------------------------------------------------------------------------

    public static String questionSystem(boolean allowAllNone) {
        return """
                You write multiple-choice questions for university students from approved study notes. The notes can be \
                in English, Tamil, or both; write the questions in the same language as the notes.

                Every question:
                - has exactly four options and exactly one correct answer, unambiguously correct by the notes;
                - has four clearly different options of similar length and style, with plausible wrong options;
                %s
                - has an explanation of why the answer is right, and a short reason each wrong option is wrong;
                - is answerable from the notes; is original (never copied from past papers); doesn't repeat another question.
                - style is one of BEGINNER, INTERMEDIATE, ADVANCED, APPLICATION, SCENARIO, CODE, CALCULATION, EXAM; \
                difficulty is BEGINNER, INTERMEDIATE or ADVANCED. CODE questions put the code in "code"; CALCULATION \
                questions have one numeric answer.

                Reply with JSON only:
                {"questions": [{"style": "APPLICATION", "difficulty": "INTERMEDIATE", "stem": "...",
                  "code": {"language": "sql", "text": "..."} or null, "options": ["...", "...", "...", "..."],
                  "answer": "B", "explanation": "...", "optionExplanations": ["...", "...", "...", "..."],
                  "subtopic": "...", "source": "the notes section it comes from"}]}
                """.formatted(allowAllNone ? "- may use \"all of the above\" or \"none of the above\" sparingly;"
                : "- never uses \"all of the above\", \"none of the above\", \"both\" or similar options;");
    }

    /** Added to the question system prompt when an approved topic analysis plans the questions (E11). */
    public static final String PLANNED = """

            When the request has a plan from the topic analysis, follow it: each question tests the objective it \
            is planned for, and its JSON has "objective": the objective's id (for example "O2"). Questions still \
            come only from the notes; if the notes can't support a planned question, write fewer rather than \
            inventing content.
            """;

    public static String questionPrompt(String topic, String notesText, int count, List<String> difficulties, List<String> styles,
                                        List<String> existingStems, String guidance, Question replacing) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(topic).append('\n');
        if (replacing != null) {
            sb.append("Write one new question to replace this one (keep its subtopic, style and difficulty unless the guidance says otherwise):\n")
                    .append(replacing.stem()).append('\n');
        } else {
            sb.append("Write ").append(count).append(count == 1 ? " question" : " questions").append(".\n");
        }
        if (!difficulties.isEmpty()) sb.append("Difficulties to use: ").append(String.join(", ", difficulties)).append('\n');
        if (!styles.isEmpty()) sb.append("Styles to use, spread evenly: ").append(String.join(", ", styles)).append('\n');
        if (guidance != null && !guidance.isBlank()) sb.append("Guidance from the lecturer: ").append(guidance.strip()).append('\n');
        if (!existingStems.isEmpty()) {
            sb.append("\nThe bank already has these questions; don't repeat them:\n");
            existingStems.stream().limit(150).forEach(s -> sb.append("- ").append(oneLine(s)).append('\n'));
        }
        sb.append("\n<notes>\n").append(notesText).append("\n</notes>\n");
        return sb.toString();
    }

    /** Accepted questions, and why the others were dropped. */
    public record Drafted(List<Question> accepted, List<String> dropped) {
    }

    public static Drafted readQuestions(JsonNode reply, List<Question> existing, boolean allowAllNone, String notesLabel) {
        return readQuestions(reply, existing, allowAllNone, notesLabel, Set.of());
    }

    /**
     * As above; with a topic analysis plan (E11), a question that names one of the plan's
     * objectives has the objective's id put before its subtopic ("O2: ..."), where people can see
     * and edit it.
     */
    public static Drafted readQuestions(JsonNode reply, List<Question> existing, boolean allowAllNone, String notesLabel, Set<String> objectives) {
        List<Question> accepted = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        existing.forEach(q -> ids.add(q.id()));
        int n = 0;
        for (JsonNode j : reply.path("questions")) {
            n++;
            List<String> opts = new ArrayList<>();
            j.path("options").forEach(o -> opts.add(o.asText("")));
            List<String> why = new ArrayList<>();
            j.path("optionExplanations").forEach(o -> why.add(o.asText("")));
            Code code = j.path("code").isObject() && !j.path("code").path("text").asText("").isBlank()
                    ? new Code(j.path("code").path("language").asText(null), j.path("code").path("text").asText()) : null;
            String source = text(j, "source");
            Question raw = new Question(null, upper(text(j, "style")), upper(text(j, "difficulty")), text(j, "stem"), code,
                    opts.size() == 4 ? opts : null, answer(j.path("answer")), text(j, "explanation"), why.size() == 4 ? why : null,
                    planned(text(j, "objective"), text(j, "subtopic"), objectives), new Reference(source != null ? notesLabel + ": " + source : notesLabel, null, null, null, null, null),
                    "UNVERIFIED");
            if (raw.options() == null) {
                dropped.add("question " + n + ": not four options");
                continue;
            }
            Question q;
            try {
                q = McqRules.clean(raw, ids);
            } catch (RuntimeException e) {
                dropped.add("question " + n + ": " + e.getMessage());
                continue;
            }
            List<String> problems = McqRules.problems(q, allowAllNone);
            if (!problems.isEmpty()) {
                dropped.add("question " + n + ": " + String.join(", ", problems));
                continue;
            }
            Question twin = java.util.stream.Stream.concat(existing.stream(), accepted.stream())
                    .filter(x -> McqRules.similarity(x.stem(), q.stem()) >= McqRules.DUPLICATE).findFirst().orElse(null);
            if (twin != null) {
                dropped.add("question " + n + ": repeats an existing question");
                continue;
            }
            accepted.add(q);
        }
        return new Drafted(accepted, dropped);
    }

    /** "B", "b", "2" (1-based) or 1 (0-based index): the key as 0 to 3. */
    static Integer answer(JsonNode a) {
        if (a.isInt()) {
            int v = a.asInt();
            return v >= 0 && v <= 3 ? v : null;
        }
        String s = a.asText("").strip().toUpperCase(Locale.ROOT);
        if (s.length() >= 1 && s.charAt(0) >= 'A' && s.charAt(0) <= 'D' && (s.length() == 1 || !Character.isLetter(s.charAt(1)))) {
            return s.charAt(0) - 'A';
        }
        if (s.matches("[1-4]")) return Integer.parseInt(s) - 1;
        return null;
    }

    static String planned(String objective, String subtopic, Set<String> objectives) {
        String o = objective == null ? null : objective.strip();
        if (o == null || !objectives.contains(o)) return subtopic;
        return o + ": " + (subtopic == null || subtopic.isBlank() ? "planned question" : subtopic);
    }

    private static String upper(String s) {
        return s == null ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    private static String attr(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("<", "(").replace(">", ")");
    }
}
