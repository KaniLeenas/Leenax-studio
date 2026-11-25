package com.leenax.studio.topicanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.notes.MaterialsAi.Passage;
import com.leenax.studio.notes.MaterialsAi.TopicBrief;
import com.leenax.studio.syllabus.SyllabusAnalysis;
import com.leenax.studio.topicanalysis.TopicAnalysis.Concept;
import com.leenax.studio.topicanalysis.TopicAnalysis.Difficulty;
import com.leenax.studio.topicanalysis.TopicAnalysis.Example;
import com.leenax.studio.topicanalysis.TopicAnalysis.Item;
import com.leenax.studio.topicanalysis.TopicAnalysis.Made;
import com.leenax.studio.topicanalysis.TopicAnalysis.McqPlan;
import com.leenax.studio.topicanalysis.TopicAnalysis.Misconception;
import com.leenax.studio.topicanalysis.TopicAnalysis.Note;
import com.leenax.studio.topicanalysis.TopicAnalysis.Objective;
import com.leenax.studio.topicanalysis.TopicAnalysis.Scene;
import com.leenax.studio.topicanalysis.TopicAnalysis.Source;
import com.leenax.studio.topicanalysis.TopicAnalysis.VideoPlan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Prompt 1 (E11): the topic analysis prompt, and reading its answer. */
public final class TopicAnalysisAi {

    private TopicAnalysisAi() {
    }

    public static final String SYSTEM = """
            You analyse one topic of a university module so that a lecturer can approve a teaching plan before any \
            questions or videos are made. You are given the topic, where it sits in the approved topic list, the module's \
            learning outcomes, and the course document pages that mention it. Documents can be in English, Tamil, or both.

            Rules:
            - Use only the pages given for facts. Where you add standard general knowledge, mark the item "basis": "GENERAL"; \
            everything from the pages is "basis": "DOCUMENT" with sources.
            - Every DOCUMENT item cites where it comes from: {"doc": "D1", "page": 3, "quote": "..."}. The quote is 3 to 15 \
            words copied exactly from that page, in the page's own language and spelling.
            - Never invent exam questions, marks, weights, hours or past-paper frequency. Report exam relevance only when a page \
            shows it (a past paper, a marking scheme, a stated weight), with its source.
            - Keep terms in the language the documents use. Don't translate.
            - objectives: 3 to 6, each starting with a measurable verb, each with a Bloom level (REMEMBER, UNDERSTAND, APPLY, \
            ANALYSE, EVALUATE or CREATE) and the module outcome codes it serves.
            - concepts: the ideas a student must hold, in teaching order, each with a one-sentence definition.
            - misconceptions: mistakes students really make with this topic; each one becomes a source of wrong options in \
            multiple-choice questions, so state the wrong belief and the correct one.
            - difficulty is BEGINNER, INTERMEDIATE or ADVANCED, with one sentence saying why.
            - mcqPlan: how many of the questions asked for each objective deserves, split by difficulty (BEGINNER, \
            INTERMEDIATE, ADVANCED), and which styles fit (BEGINNER, INTERMEDIATE, ADVANCED, APPLICATION, SCENARIO, CODE, \
            CALCULATION, EXAM). The counts add up exactly to the number asked. CODE only if the subject uses code; \
            CALCULATION only if it uses numbers.
            - videoPlan: a lesson of the length asked as 5 to 9 scenes, each with a goal (an objective id), what is shown \
            (CONCEPT, DEFINITION, DIAGRAM, EXAMPLE, COMPARISON, APPLICATIONS or SUMMARY) and an estimated length in seconds; \
            the seconds add up to the length asked.
            - gaps: outcomes or subtopics the pages don't cover well enough to teach. questions: anything the lecturer must \
            decide. Leave either empty if there is nothing.

            Reply with JSON only, in this shape:
            {"summary": "two or three sentences",
             "difficulty": {"level": "INTERMEDIATE", "why": "..."},
             "prerequisites": [{"text": "...", "basis": "DOCUMENT", "sources": [...]}],
             "objectives": [{"id": "O1", "text": "...", "bloom": "APPLY", "outcomes": ["LO2"], "sources": [...]}],
             "concepts": [{"id": "C1", "term": "...", "definition": "...", "basis": "DOCUMENT", "sources": [...]}],
             "misconceptions": [{"wrong": "...", "right": "...", "concept": "C2", "basis": "GENERAL"}],
             "examples": [{"title": "...", "kind": "WORKED", "concepts": ["C1"], "sources": [...]}],
             "examRelevance": [{"text": "...", "sources": [...]}],
             "mcqPlan": [{"objective": "O1", "count": 4, "difficulties": {"BEGINNER": 1, "INTERMEDIATE": 2, "ADVANCED": 1},
                          "styles": ["APPLICATION", "SCENARIO"]}],
             "videoPlan": {"hook": "one sentence that opens the video",
                           "scenes": [{"title": "...", "goal": "O1", "shows": "CONCEPT", "seconds": 60, "concepts": ["C1"]}]},
             "gaps": [{"text": "..."}],
             "questions": [{"text": "..."}]}
            """;

    public static String prompt(TopicBrief t, List<Passage> passages, int mcqCount, int videoMinutes, String level, String guidance) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(t.title()).append('\n');
        if (t.path() != null) sb.append("Where it sits: ").append(t.path()).append('\n');
        if (!t.subtopics().isEmpty()) sb.append("Subtopics: ").append(String.join("; ", t.subtopics())).append('\n');
        if (!t.prerequisites().isEmpty()) sb.append("Prerequisite topics in the list: ").append(String.join("; ", t.prerequisites())).append('\n');
        if (t.difficulty() != null) sb.append("Difficulty in the topic list: ").append(t.difficulty().toLowerCase(Locale.ROOT)).append('\n');
        if (!t.outcomes().isEmpty()) sb.append("Module learning outcomes:\n- ").append(String.join("\n- ", t.outcomes())).append('\n');
        sb.append("Questions to plan for: ").append(mcqCount).append('\n');
        sb.append("Lesson video length: about ").append(videoMinutes).append(" minutes, level ").append(Learning.levelLabel(level)).append('\n');
        if (guidance != null && !guidance.isBlank()) sb.append("Guidance from the lecturer (follow it unless it conflicts with the rules): ")
                .append(guidance.strip()).append('\n');
        sb.append("\nCourse document pages:\n");
        for (Passage p : passages) {
            sb.append("\n<page doc=\"").append(p.label()).append("\" title=\"").append(attr(p.title())).append("\" n=\"").append(p.page())
                    .append("\">\n").append(p.text().strip()).append("\n</page>\n");
        }
        if (passages.isEmpty()) sb.append("(none found: mark every item GENERAL and cite nothing)\n");
        return sb.toString();
    }

    // ---- reading the answer ------------------------------------------------------------------------

    static final class Tally {
        int kept;
        int found;
        int removed;
    }

    public static TopicAnalysis read(JsonNode reply, TopicBrief t, UUID topicsVersionId, List<Passage> passages, int mcqCount,
                                     int videoMinutes, String model) {
        Tally tally = new Tally();
        JsonNode d = reply.path("difficulty");
        Difficulty difficulty = d.isObject() ? new Difficulty(upper(str(d, "level")), str(d, "why")) : null;
        List<Item> prerequisites = items(reply.path("prerequisites"), passages, tally);
        List<Objective> objectives = new ArrayList<>();
        for (JsonNode o : reply.path("objectives")) {
            if (str(o, "text") == null) continue;
            objectives.add(new Objective(str(o, "id"), str(o, "text"), upper(str(o, "bloom")), strings(o.path("outcomes")), sources(o, passages, tally)));
        }
        List<Concept> concepts = new ArrayList<>();
        for (JsonNode c : reply.path("concepts")) {
            if (str(c, "term") == null) continue;
            concepts.add(new Concept(str(c, "id"), str(c, "term"), str(c, "definition"), upper(str(c, "basis")), sources(c, passages, tally)));
        }
        List<Misconception> misconceptions = new ArrayList<>();
        int n = 0;
        for (JsonNode m : reply.path("misconceptions")) {
            if (str(m, "wrong") == null) continue;
            misconceptions.add(new Misconception(++n, str(m, "wrong"), str(m, "right"), str(m, "concept"), upper(str(m, "basis"))));
        }
        List<Example> examples = new ArrayList<>();
        for (JsonNode e : reply.path("examples")) {
            if (str(e, "title") == null) continue;
            examples.add(new Example(str(e, "title"), upper(str(e, "kind")), strings(e.path("concepts")), sources(e, passages, tally)));
        }
        List<Item> exam = items(reply.path("examRelevance"), passages, tally);
        List<McqPlan> plan = new ArrayList<>();
        for (JsonNode p : reply.path("mcqPlan")) {
            Map<String, Integer> diff = new LinkedHashMap<>();
            p.path("difficulties").fields().forEachRemaining(f -> {
                if (f.getValue().canConvertToInt()) diff.put(f.getKey().toUpperCase(Locale.ROOT), f.getValue().asInt());
            });
            plan.add(new McqPlan(str(p, "objective"), p.path("count").canConvertToInt() ? p.path("count").asInt() : null, diff,
                    strings(p.path("styles")).stream().map(x -> x.toUpperCase(Locale.ROOT)).toList()));
        }
        JsonNode v = reply.path("videoPlan");
        List<Scene> scenes = new ArrayList<>();
        for (JsonNode s : v.path("scenes")) {
            if (str(s, "title") == null) continue;
            scenes.add(new Scene(str(s, "title"), str(s, "goal"), upper(str(s, "shows")), s.path("seconds").canConvertToInt() ? s.path("seconds").asInt() : null,
                    strings(s.path("concepts"))));
        }
        VideoPlan video = new VideoPlan(str(v, "hook"), scenes);
        List<Note> gaps = notes(reply.path("gaps")), questions = notes(reply.path("questions"));
        Made made = new Made(model, Instant.now().toString(), tally.kept, tally.found, tally.removed);
        return new TopicAnalysis(t.id(), t.title(), t.path(), topicsVersionId, str(reply, "summary"), difficulty, prerequisites, objectives,
                concepts, misconceptions, examples, exam, mcqCount, plan, videoMinutes, video, gaps, questions, made);
    }

    private static List<Item> items(JsonNode arr, List<Passage> passages, Tally tally) {
        List<Item> out = new ArrayList<>();
        for (JsonNode i : arr) {
            if (str(i, "text") == null) continue;
            out.add(new Item(str(i, "text"), upper(str(i, "basis")), sources(i, passages, tally)));
        }
        return out;
    }

    private static List<Note> notes(JsonNode arr) {
        List<Note> out = new ArrayList<>();
        for (JsonNode i : arr) {
            String text = i.isTextual() ? i.asText() : str(i, "text");
            if (text != null && !text.isBlank()) out.add(new Note(text.strip()));
        }
        return out;
    }

    /**
     * The item's citations that point at a page it was given, each marked found if its quote is on
     * that page word for word. Citations of pages it wasn't given are removed and counted.
     */
    static List<Source> sources(JsonNode item, List<Passage> passages, Tally tally) {
        List<Source> out = new ArrayList<>();
        for (JsonNode s : item.path("sources")) {
            String label = str(s, "doc");
            int page = s.path("page").asInt(-1);
            Passage p = passages.stream().filter(x -> x.label().equalsIgnoreCase(label == null ? "" : label) && x.page() == page).findFirst().orElse(null);
            if (p == null) {
                tally.removed++;
                continue;
            }
            String quote = str(s, "quote");
            boolean found = quote != null && SyllabusAnalysis.contains(p.text(), quote);
            tally.kept++;
            if (found) tally.found++;
            out.add(new Source(p.documentId(), p.versionId(), p.title(), page, quote, found));
        }
        return out;
    }

    static String str(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> {
            if (x.isTextual() && !x.asText().isBlank()) out.add(x.asText().strip());
        });
        return out;
    }

    static String attr(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("<", "").replace(">", "");
    }
}
