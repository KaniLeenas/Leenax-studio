package com.leenax.studio.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.slides.Slides.Code;
import com.leenax.studio.slides.Slides.Deck;
import com.leenax.studio.slides.Slides.Figure;
import com.leenax.studio.slides.Slides.Image;
import com.leenax.studio.slides.Slides.Slide;
import com.leenax.studio.slides.Slides.Table;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A deck as an approval stage (E7). Cleaning keeps only what each slide's layout shows and asks
 * the document service how the text fits; text that doesn't fit, or a fit that couldn't be
 * checked, stops approval. Accuracy and source checks, which need the notes, are in SlidesStore.
 */
@Component
public class SlidesRules implements Review.ContentHandler {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_WORDS_PER_BULLET = 25;
    static final int MAX_BULLETS_READABLE = 6;
    private final SlideFitter fitter;

    public SlidesRules(SlideFitter fitter) {
        this.fitter = fitter;
    }

    @Override
    public Kind kind() {
        return Kind.SLIDES;
    }

    @Override
    public JsonNode normalise(JsonNode raw, UUID dependsOn) {
        Deck d = read(raw);
        if (d.topicId() == null || d.topicId().isBlank() || d.level() == null || !Learning.LEVELS.contains(d.level())) {
            throw ApiException.badRequest("A presentation needs its topic and notes level");
        }
        List<Slide> in = Slides.list(d.slides());
        if (in.size() > Slides.MAX_SLIDES) throw ApiException.badRequest("At most " + Slides.MAX_SLIDES + " slides");
        List<Slide> slides = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Slide s : in) {
            if (s == null) continue;
            slides.add(clean(s, ids));
        }
        String style = Slides.STYLES.containsKey(d.style()) ? d.style() : "LECTURE";
        Deck clean = new Deck(d.topicId().strip(), clip(d.topicTitle(), 200), d.level(), d.notesVersionId(), d.qaVersionId(), d.seoVersionId(), style,
                clip(d.title(), 200), slides, null, clip(d.model(), 100));
        JsonNode tree = JSON.valueToTree(clean);
        JsonNode fit = fitter.fit(tree);
        return JSON.valueToTree(new Deck(clean.topicId(), clean.topicTitle(), clean.level(), clean.notesVersionId(), clean.qaVersionId(),
                clean.seoVersionId(), clean.style(), clean.title(), clean.slides(), fit, clean.model()));
    }

    /** One slide: a known type and layout, an id, trimmed text, and only the fields its layout shows. */
    public static Slide clean(Slide s, Set<String> ids) {
        String type = s.type() == null ? "CONCEPT" : s.type().strip().toUpperCase(Locale.ROOT);
        if (!Slides.TYPES.contains(type)) type = "CONCEPT";
        String layout = s.layout() == null ? null : s.layout().strip().toUpperCase(Locale.ROOT);
        if (layout == null || !Slides.LAYOUTS.contains(layout)) layout = Slides.defaultLayout(type);
        String id = s.id() == null || !s.id().matches("[A-Za-z0-9_-]{1,40}") || ids.contains(s.id()) ? "s" + UUID.randomUUID().toString().substring(0, 8) : s.id();
        ids.add(id);
        List<String> f = Slides.fields(layout);
        List<String> bullets = null;
        if (f.contains("bullets")) {
            bullets = new ArrayList<>();
            for (String b : Slides.list(s.bullets())) {
                String t = line(b, 400);
                if (t != null) bullets.add(t);
            }
            if (bullets.size() > Slides.MAX_BULLETS) throw ApiException.badRequest("At most " + Slides.MAX_BULLETS + " bullets on a slide");
        }
        Table table = f.contains("table") ? table(s.table()) : null;
        Figure figure = null;
        if (f.contains("figure") && s.figure() != null) {
            String kind = "MATH".equalsIgnoreCase(s.figure().kind()) ? "MATH" : "MERMAID";
            String src = s.figure().source() == null ? "" : s.figure().source().strip();
            if (src.length() > 20_000) throw ApiException.badRequest("A diagram's source is at most 20,000 characters");
            figure = src.isEmpty() ? null : new Figure(kind, src);
        }
        Image image = f.contains("image") && s.image() != null && s.image().assetId() != null ? new Image(s.image().assetId(), clip(s.image().alt(), 300)) : null;
        Code code = null;
        if (f.contains("code") && s.code() != null && s.code().text() != null && !s.code().text().isBlank()) {
            String text = s.code().text().replace("\r\n", "\n").replace("\t", "    ").stripTrailing();
            if (text.length() > 4000) throw ApiException.badRequest("Code on a slide is at most 4,000 characters");
            code = new Code(clip(s.code().language(), 30), text);
        }
        List<String> sources = Slides.list(s.sources()).stream().map(x -> line(x, 200)).filter(Objects::nonNull).distinct().limit(10).toList();
        return new Slide(id, type, layout, line(s.title(), 200), f.contains("subtitle") ? line(s.subtitle(), 300) : null, bullets,
                f.contains("term") ? line(s.term(), 200) : null, f.contains("text") ? text(s.text(), 2000) : null, table, figure, image,
                f.contains("caption") ? line(s.caption(), 300) : null, code, text(s.notes(), 5000), sources.isEmpty() ? null : sources);
    }

    static Table table(Table t) {
        if (t == null) return null;
        List<String> headers = Slides.list(t.headers()).stream().map(h -> h == null ? "" : clipOrEmpty(h, 200)).toList();
        List<List<String>> rows = new ArrayList<>();
        for (List<String> r : Slides.list(t.rows())) {
            if (r == null) continue;
            rows.add(r.stream().map(c -> c == null ? "" : clipOrEmpty(c, 300)).toList());
        }
        int cols = Math.max(headers.size(), rows.stream().mapToInt(List::size).max().orElse(0));
        if (cols > 8) throw ApiException.badRequest("A table on a slide has at most 8 columns");
        if (rows.size() > 15) throw ApiException.badRequest("A table on a slide has at most 15 rows");
        if (headers.isEmpty() && rows.isEmpty()) return null;
        return new Table(headers, rows);
    }

    // ---- checks ------------------------------------------------------------------------------------

    @Override
    public List<Check> checks(JsonNode content, Context ctx) {
        Deck d = read(content);
        List<Slide> slides = Slides.list(d.slides());
        List<Check> out = new ArrayList<>();
        if (slides.isEmpty()) {
            out.add(Check.fail("slides", "The presentation has no slides"));
            return out;
        }
        out.add(slides.size() >= 3 ? Check.pass("slides", slides.size() + " slides") : Check.warn("slides", "Only " + slides.size() + " slide(s)"));

        // layout overflow: the document service's measurement
        JsonNode fit = d.fit();
        if (fit == null || !fit.path("checked").asBoolean(false)) {
            out.add(Check.fail("fit", "The text fit couldn't be checked (" + (fit == null ? "not measured" : fit.path("error").asText("unknown reason"))
                    + "). Save again once the document service is running."));
        } else {
            List<String> over = new ArrayList<>(), smallest = new ArrayList<>();
            for (JsonNode s : fit.path("slides")) {
                int n = number(slides, s.path("id").asText());
                for (JsonNode p : s.path("problems")) over.add("slide " + n + ": " + p.asText());
                for (JsonNode b : s.path("boxes")) {
                    if (b.path("fits").asBoolean() && b.path("size").asDouble() <= b.path("min").asDouble() && b.path("max").asDouble() > b.path("min").asDouble()) {
                        smallest.add("slide " + n);
                        break;
                    }
                }
            }
            out.add(over.isEmpty() ? Check.pass("overflow", "All text fits its box at a readable size")
                    : Check.fail("overflow", "Text doesn't fit: " + String.join("; ", over.stream().limit(5).toList()) + (over.size() > 5 ? " …" : "")));
            if (!smallest.isEmpty()) {
                out.add(Check.warn("small-text", "Shrunk to the smallest size allowed on " + String.join(", ", smallest.stream().distinct().limit(8).toList())
                        + "; consider splitting those slides"));
            }
        }

        // what each layout needs
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < slides.size(); i++) {
            Slide s = slides.get(i);
            String what = switch (s.layout()) {
                case "DIAGRAM" -> s.figure() == null ? "a diagram" : null;
                case "IMAGE", "BULLETS_IMAGE" -> s.image() == null ? "an image" : null;
                case "TABLE" -> s.table() == null ? "a table" : null;
                case "EXAMPLE" -> s.code() == null && (s.text() == null || s.text().isBlank()) ? "an example" : null;
                case "DEFINITION" -> s.term() == null || s.text() == null ? "a term and its definition" : null;
                case "BULLETS" -> Slides.list(s.bullets()).isEmpty() ? "bullet points" : null;
                default -> null;
            };
            if (s.title() == null) what = what == null ? "a title" : what + " and a title";
            if (what != null) missing.add("slide " + (i + 1) + " needs " + what);
        }
        out.add(missing.isEmpty() ? Check.pass("complete", "Every slide has what its layout shows")
                : Check.fail("complete", String.join("; ", missing.stream().limit(6).toList())));

        // readability
        List<String> dense = new ArrayList<>();
        for (int i = 0; i < slides.size(); i++) {
            List<String> b = Slides.list(slides.get(i).bullets());
            if (b.size() > MAX_BULLETS_READABLE || b.stream().anyMatch(x -> words(x) > MAX_WORDS_PER_BULLET)) dense.add("slide " + (i + 1));
        }
        out.add(dense.isEmpty() ? Check.pass("readability", "At most " + MAX_BULLETS_READABLE + " bullets of up to " + MAX_WORDS_PER_BULLET + " words each")
                : Check.warn("readability", "Dense text on " + String.join(", ", dense.stream().limit(8).toList()) + ": more than "
                + MAX_BULLETS_READABLE + " bullets or a bullet over " + MAX_WORDS_PER_BULLET + " words"));

        // the shape of a lesson
        out.add("TITLE".equals(slides.get(0).type()) ? Check.pass("title-slide", "Starts with a title slide")
                : Check.warn("title-slide", "The first slide isn't a title slide"));
        out.add(slides.stream().anyMatch(s -> "REFERENCES".equals(s.type())) ? Check.pass("references", "Has a references slide")
                : Check.warn("references", "No references slide"));
        List<String> noNotes = new ArrayList<>();
        for (int i = 0; i < slides.size(); i++) {
            Slide s = slides.get(i);
            if (!"TITLE".equals(s.type()) && !"REFERENCES".equals(s.type()) && (s.notes() == null || s.notes().isBlank())) noNotes.add(String.valueOf(i + 1));
        }
        out.add(noNotes.isEmpty() ? Check.pass("speaker-notes", "Every teaching slide has speaker notes")
                : Check.warn("speaker-notes", "No speaker notes on slide" + (noNotes.size() > 1 ? "s " : " ") + String.join(", ", noNotes.stream().limit(10).toList())));
        return out;
    }

    @Override
    public List<String> diff(JsonNode before, JsonNode after) {
        Deck a = read(before), b = read(after);
        List<String> out = new ArrayList<>();
        if (!Objects.equals(a.style(), b.style())) out.add("Style: " + Slides.STYLES.getOrDefault(b.style(), b.style()));
        List<Slide> x = Slides.list(a.slides()), y = Slides.list(b.slides());
        Set<String> before2 = new HashSet<>(), after2 = new HashSet<>();
        x.forEach(s -> before2.add(s.id()));
        y.forEach(s -> after2.add(s.id()));
        long added = y.stream().filter(s -> !before2.contains(s.id())).count();
        long removed = x.stream().filter(s -> !after2.contains(s.id())).count();
        if (added > 0) out.add(added + " slide" + (added == 1 ? "" : "s") + " added");
        if (removed > 0) out.add(removed + " slide" + (removed == 1 ? "" : "s") + " removed");
        List<String> orderA = x.stream().map(Slide::id).filter(after2::contains).toList();
        List<String> orderB = y.stream().map(Slide::id).filter(before2::contains).toList();
        if (!orderA.equals(orderB)) out.add("Slides reordered");
        int changed = 0;
        for (Slide s : y) {
            for (Slide t : x) {
                if (t.id().equals(s.id()) && !JSON.valueToTree(t).equals(JSON.valueToTree(s))) changed++;
            }
        }
        if (changed > 0) out.add(changed + " slide" + (changed == 1 ? "" : "s") + " changed");
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------------

    public static Deck read(JsonNode raw) {
        try {
            return JSON.treeToValue(raw, Deck.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Not a presentation");
        }
    }

    static int number(List<Slide> slides, String id) {
        for (int i = 0; i < slides.size(); i++) if (slides.get(i).id().equals(id)) return i + 1;
        return 0;
    }

    static int words(String s) {
        return s == null || s.isBlank() ? 0 : s.strip().split("\\s+").length;
    }

    /** Trimmed single-line text; too long is refused rather than cut, so nothing changes unseen. */
    static String line(String s, int max) {
        if (s == null) return null;
        String t = s.strip().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : limit(t, max);
    }

    static String text(String s, int max) {
        if (s == null) return null;
        String t = s.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
        return t.isEmpty() ? null : limit(t, max);
    }

    static String limit(String t, int max) {
        if (t.length() <= max) return t;
        String start = t.length() > 40 ? t.substring(0, 40) + "…" : t;
        throw ApiException.badRequest("Too long for a slide (at most " + max + " characters): “" + start + "”");
    }

    static String clip(String s, int max) {
        return line(s, max);
    }

    static String clipOrEmpty(String s, int max) {
        String t = line(s, max);
        return t == null ? "" : t;
    }
}
