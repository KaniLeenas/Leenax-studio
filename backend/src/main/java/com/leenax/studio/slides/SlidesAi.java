package com.leenax.studio.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.notes.NoteDoc;
import com.leenax.studio.slides.Slides.Code;
import com.leenax.studio.slides.Slides.Figure;
import com.leenax.studio.slides.Slides.Slide;
import com.leenax.studio.slides.Slides.Table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Prompts for making slides from approved notes, and how the replies are read. The AI works only
 * from the notes; references are never written by it (Leenax lists the notes' own references).
 * Replies are cut to the slide limits here, before anyone sees the draft.
 */
public final class SlidesAi {

    private SlidesAi() {
    }

    /** What the AI is given about the topic. */
    public record Brief(String subject, String topicTitle, String path, String levelLabel, String notesText, List<String> headings,
                        List<NoteDoc.Figure> figures, String videoTitle, List<String> keywords, int slideCount, String guidance) {
    }

    static final String SHAPE = """
            Each slide is a JSON object with "type" (one of TITLE, OBJECTIVES, INTRODUCTION, OVERVIEW, DEFINITION, CONCEPT, DIAGRAM, \
            FLOWCHART, EXAMPLE, COMPARISON, APPLICATIONS, EXAM_TIPS, SUMMARY, REVISION), "title", and by type:
            - TITLE: "subtitle".
            - DEFINITION: "term" and "text" (the definition, one or two sentences).
            - DIAGRAM or FLOWCHART: "figureRef" (the id of one of the notes' diagrams, like "F2") or "figure": {"kind": "MERMAID", \
            "source": "flowchart TD ..."} for a new, simple Mermaid diagram (at most 8 nodes, short labels); and "caption".
            - EXAMPLE: "text" (one or two sentences) and, for code or SQL, "code": {"language": "sql", "text": "..."} (at most 14 lines \
            of 60 characters).
            - COMPARISON: "table": {"headers": [...], "rows": [[...], ...]} (at most 4 columns and 6 rows, a few words per cell).
            - every other type: "bullets" (3 to 6, each at most 16 words).
            And on every slide except TITLE: "notes" (speaker notes, 60 to 140 words, said aloud while the slide shows) and "sources" \
            (the notes headings it draws on, copied exactly).""";

    static final String RULES = """
            Use only what the approved notes say: no new facts, figures, dates, names or examples that aren't in them. Write in your own \
            words; don't copy long sentences. Revision questions are new ones you write, never questions copied from an exam paper. \
            Slide titles are at most 60 characters. Keep slides short: a slide is read in seconds while the speaker explains.
            Don't write a references slide: Leenax adds the notes' own references.""";

    public static String deckSystem() {
        return """
                You turn approved university lesson notes into a teaching slide deck.
                %s
                %s
                Order: TITLE, OBJECTIVES, then INTRODUCTION or OVERVIEW, then the teaching slides in the notes' order (definitions, \
                concepts, diagrams, examples, comparisons, applications), then EXAM_TIPS, SUMMARY and REVISION.
                Reply with JSON only: {"title": "deck title", "slides": [ ... ]}.
                """.formatted(SHAPE, RULES);
    }

    /** Added to the deck system prompt when an approved topic analysis plans the video (E11). */
    public static final String PLANNED = """

            When the request has a video plan from the topic analysis, follow its scenes in order: each scene \
            becomes one or two slides, its speaker notes are the narration for that scene and run to about the \
            words given, and the first content slide delivers the hook. Content still comes only from the notes.
            """;

    public static String deckPrompt(Brief b) {
        StringBuilder sb = new StringBuilder();
        if (b.subject() != null) sb.append("Subject: ").append(b.subject()).append('\n');
        sb.append("Topic: ").append(b.topicTitle()).append('\n');
        if (b.path() != null) sb.append("Where it sits: ").append(b.path()).append('\n');
        sb.append("Level: ").append(b.levelLabel()).append('\n');
        sb.append("Slides: about ").append(b.slideCount()).append('\n');
        if (b.videoTitle() != null) sb.append("The lesson video's approved title: ").append(b.videoTitle()).append('\n');
        if (!b.keywords().isEmpty()) sb.append("Approved keywords (use them where they fit naturally): ").append(String.join("; ", b.keywords())).append('\n');
        if (b.guidance() != null && !b.guidance().isBlank()) sb.append("Guidance: ").append(b.guidance()).append('\n');
        figures(sb, b.figures());
        sb.append("Headings of the notes: ").append(String.join("; ", b.headings().stream().limit(60).toList())).append('\n');
        sb.append("\nThe approved notes:\n").append(b.notesText());
        return sb.toString();
    }

    public static String slideSystem() {
        return """
                You rewrite one slide of a teaching deck made from approved university lesson notes.
                %s
                %s
                Keep the slide's purpose unless the guidance asks otherwise. Reply with JSON only: {"slide": { ... }}.
                """.formatted(SHAPE, RULES);
    }

    public static String slidePrompt(Brief b, List<String> outline, int number, JsonNode current) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topic: ").append(b.topicTitle()).append(" (").append(b.levelLabel()).append(")\n");
        sb.append("The deck's slides:\n");
        for (int i = 0; i < outline.size(); i++) sb.append(i + 1).append(". ").append(outline.get(i)).append('\n');
        sb.append("Rewrite slide ").append(number).append(". It is now:\n").append(current.toString()).append('\n');
        if (b.guidance() != null && !b.guidance().isBlank()) sb.append("Guidance: ").append(b.guidance()).append('\n');
        figures(sb, b.figures());
        sb.append("\nThe approved notes:\n").append(b.notesText());
        return sb.toString();
    }

    private static void figures(StringBuilder sb, List<NoteDoc.Figure> figures) {
        List<NoteDoc.Figure> diagrams = figures.stream().filter(f -> f.kind().equals("MERMAID")).limit(12).toList();
        if (diagrams.isEmpty()) return;
        sb.append("Diagrams in the notes (reuse them by id):\n");
        for (int i = 0; i < diagrams.size(); i++) {
            String src = diagrams.get(i).source().strip();
            sb.append("F").append(i + 1).append(":\n").append(src.length() > 1500 ? src.substring(0, 1500) : src).append('\n');
        }
    }

    // ---- reading replies ---------------------------------------------------------------------------

    /** The slides of a deck reply. References slides from the AI are dropped. */
    public static List<Slide> readDeck(JsonNode reply, List<NoteDoc.Figure> figures) {
        List<Slide> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode s : reply.path("slides")) {
            Slide slide = readSlide(s, figures, null);
            if (slide == null || "REFERENCES".equals(slide.type())) continue;
            while (!ids.add(slide.id())) slide = withId(slide, newId());
            out.add(slide);
            if (out.size() >= Slides.MAX_SLIDES - 1) break;
        }
        return out;
    }

    /** One slide from the reply, cut to the limits; null if it has no title. */
    public static Slide readSlide(JsonNode s, List<NoteDoc.Figure> figures, String keepId) {
        if (s == null || !s.isObject()) return null;
        String type = s.path("type").asText("").strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!Slides.TYPES.contains(type)) type = "CONCEPT";
        String title = cut(s.path("title").asText(null), 120);
        if (title == null) return null;
        String layout = Slides.defaultLayout(type);
        List<String> bullets = new ArrayList<>();
        for (JsonNode b : s.path("bullets")) {
            String t = cut(b.asText(null), 300);
            if (t != null && bullets.size() < 8) bullets.add(t);
        }
        Table table = null;
        if (s.path("table").isObject()) {
            List<String> headers = new ArrayList<>();
            s.path("table").path("headers").forEach(h -> { if (headers.size() < 6) headers.add(orEmpty(cut(h.asText(""), 120))); });
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode r : s.path("table").path("rows")) {
                if (rows.size() >= 10 || !r.isArray()) continue;
                List<String> row = new ArrayList<>();
                r.forEach(c -> { if (row.size() < 6) row.add(orEmpty(cut(c.asText(""), 200))); });
                rows.add(row);
            }
            if (!headers.isEmpty() || !rows.isEmpty()) table = new Table(headers, rows);
        }
        Figure figure = null;
        String ref = s.path("figureRef").asText("").strip().toUpperCase(Locale.ROOT);
        List<NoteDoc.Figure> diagrams = figures.stream().filter(f -> f.kind().equals("MERMAID")).limit(12).toList();
        if (ref.matches("F\\d{1,2}")) {
            int i = Integer.parseInt(ref.substring(1)) - 1;
            if (i >= 0 && i < diagrams.size()) figure = new Figure("MERMAID", diagrams.get(i).source().strip());
        }
        if (figure == null && s.path("figure").isObject()) {
            String src = s.path("figure").path("source").asText("").strip();
            if (!src.isEmpty() && src.length() <= 4000) figure = new Figure("MERMAID", src);
        }
        if (("DIAGRAM".equals(layout)) && figure == null) layout = "BULLETS";
        Code code = null;
        if (s.path("code").isObject()) {
            String text = s.path("code").path("text").asText("").replace("\t", "    ").stripTrailing();
            if (!text.isBlank()) code = new Code(cut(s.path("code").path("language").asText(null), 30), text.length() > 3000 ? text.substring(0, 3000) : text);
        }
        String text = cutText(s.path("text").asText(null), 1200);
        String term = cut(s.path("term").asText(null), 150);
        if ("DEFINITION".equals(layout) && (term == null || text == null)) layout = "BULLETS";
        if ("TABLE".equals(layout) && table == null) layout = "BULLETS";
        List<String> sources = new ArrayList<>();
        s.path("sources").forEach(x -> { String t = cut(x.asText(null), 200); if (t != null && sources.size() < 6) sources.add(t); });
        String id = keepId != null ? keepId : newId();
        List<String> f = Slides.fields(layout);
        return new Slide(id, type, layout, title, f.contains("subtitle") ? cut(s.path("subtitle").asText(null), 250) : null,
                f.contains("bullets") ? bullets : null, f.contains("term") ? term : null, f.contains("text") ? text : null,
                f.contains("table") ? table : null, f.contains("figure") ? figure : null, null,
                f.contains("caption") ? cut(s.path("caption").asText(null), 250) : null, f.contains("code") ? code : null,
                "TITLE".equals(type) ? null : cutText(s.path("notes").asText(null), 3000), sources.isEmpty() ? null : sources);
    }

    static Slide withId(Slide s, String id) {
        return new Slide(id, s.type(), s.layout(), s.title(), s.subtitle(), s.bullets(), s.term(), s.text(), s.table(), s.figure(), s.image(),
                s.caption(), s.code(), s.notes(), s.sources());
    }

    static String newId() {
        return "s" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max - 1).stripTrailing() + "…" : t;
    }

    static String cutText(String s, int max) {
        if (s == null) return null;
        String t = s.replace("\r\n", "\n").strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max - 1).stripTrailing() + "…" : t;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
