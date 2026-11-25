package com.leenax.studio.syllabus;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.syllabus.Content.Assessment;
import com.leenax.studio.syllabus.Content.Contradiction;
import com.leenax.studio.syllabus.Content.DocumentRef;
import com.leenax.studio.syllabus.Content.Node;
import com.leenax.studio.syllabus.Content.Outcome;
import com.leenax.studio.syllabus.Content.Source;
import com.leenax.studio.syllabus.Content.Syllabus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.leenax.studio.syllabus.Content.TEXT_MAX;
import static com.leenax.studio.syllabus.Content.TITLE_MAX;

/** What a syllabus is allowed to be, and what must hold before it is approved. */
@Component
public class SyllabusRules implements Review.ContentHandler {

    @Override
    public Kind kind() {
        return Kind.SYLLABUS;
    }

    @Override
    public JsonNode normalise(JsonNode raw, UUID dependsOn) {
        return Content.tree(clean(Content.read(raw, Syllabus.class, "syllabus")));
    }

    static Syllabus clean(Syllabus s) {
        Set<String> ids = new HashSet<>();
        int[] count = {0};
        List<Node> structure = nodes(Content.list(s.structure()), 1, ids, count);
        List<Outcome> outcomes = new ArrayList<>();
        for (Outcome o : Content.list(s.outcomes())) {
            if (o == null) continue;
            String text = Content.text(o.text(), TEXT_MAX, "A learning outcome");
            if (text == null) continue;
            outcomes.add(new Outcome(Content.id(o.id(), ids), Content.text(o.code(), 20, "An outcome code"), text, Content.sources(o.sources())));
        }
        List<Assessment> assessments = new ArrayList<>();
        for (Assessment a : Content.list(s.assessments())) {
            if (a == null) continue;
            String title = Content.text(a.title(), TITLE_MAX, "An assessment name");
            if (title == null) continue;
            String kind = a.kind() == null ? "OTHER" : a.kind().strip().toUpperCase(Locale.ROOT);
            if (!Content.ASSESSMENT_KINDS.contains(kind)) kind = "OTHER";
            Double weight = a.weight();
            if (weight != null && (weight.isNaN() || weight < 0 || weight > 100)) {
                throw ApiException.badRequest("Assessment weight for “" + title + "” must be between 0 and 100");
            }
            assessments.add(new Assessment(Content.id(a.id(), ids), title, kind, weight,
                    Content.text(a.details(), TEXT_MAX, "Assessment details"), Content.sources(a.sources())));
        }
        List<Contradiction> contradictions = new ArrayList<>();
        for (Contradiction c : Content.list(s.contradictions())) {
            if (c == null) continue;
            String d = Content.text(c.description(), TEXT_MAX, "A contradiction");
            if (d == null) continue;
            contradictions.add(new Contradiction(Content.id(c.id(), ids), d, Content.sources(c.sources()),
                    Content.text(c.resolution(), TEXT_MAX, "A resolution")));
        }
        if (outcomes.size() + assessments.size() + contradictions.size() + count[0] > Content.MAX_ITEMS) {
            throw ApiException.badRequest("A syllabus can have at most " + Content.MAX_ITEMS + " items");
        }
        List<DocumentRef> docs = new ArrayList<>();
        for (DocumentRef d : Content.list(s.documents())) {
            if (d == null || d.documentId() == null || d.versionId() == null) continue;
            docs.add(new DocumentRef(d.documentId(), d.versionId(), Content.text(d.title(), 200, "A document title"), d.versionNo(),
                    Content.text(d.label(), 10, "A document label")));
        }
        return new Syllabus(Content.text(s.summary(), 4000, "The summary"), structure, outcomes, assessments, contradictions, docs, s.analysis());
    }

    private static List<Node> nodes(List<Node> in, int depth, Set<String> ids, int[] count) {
        List<Node> out = new ArrayList<>();
        for (Node n : in) {
            if (n == null) continue;
            String title = Content.text(n.title(), TITLE_MAX, "A title");
            if (title == null) continue;
            if (depth > Content.MAX_DEPTH && n.children() != null && !n.children().isEmpty()) {
                throw ApiException.badRequest("The syllabus goes at most " + Content.MAX_DEPTH + " levels deep (chapter, topic, subtopic)");
            }
            count[0]++;
            List<Node> children = depth >= Content.MAX_DEPTH ? List.of() : nodes(Content.list(n.children()), depth + 1, ids, count);
            if (depth >= Content.MAX_DEPTH && n.children() != null && !n.children().isEmpty()) {
                throw ApiException.badRequest("“" + title + "” is a subtopic and can't have items under it");
            }
            out.add(new Node(Content.id(n.id(), ids), title, Content.sources(n.sources()), children));
        }
        return out;
    }

    // ---- checks ------------------------------------------------------------------------------------

    @Override
    public List<Check> checks(JsonNode content, Context ctx) {
        return checks(Content.read(content, Syllabus.class, "syllabus"));
    }

    static List<Check> checks(Syllabus s) {
        List<Check> out = new ArrayList<>();
        int items = countNodes(Content.list(s.structure()));
        out.add(items > 0 ? Check.pass("structure", items + " chapters, topics and subtopics")
                : Check.fail("structure", "Add at least one chapter or topic"));

        long open = Content.list(s.contradictions()).stream().filter(c -> c.resolution() == null).count();
        int all = Content.list(s.contradictions()).size();
        out.add(open == 0
                ? Check.pass("contradictions", all == 0 ? "No contradictions between documents" : "All " + all + " contradictions resolved")
                : Check.fail("contradictions", open + " of " + all + " contradictions between documents need a resolution"));

        int[] sourced = {0, 0};
        forEachSourced(s, srcs -> {
            sourced[1]++;
            if (srcs.stream().anyMatch(x -> Boolean.TRUE.equals(x.found()))) sourced[0]++;
        });
        int unsourced = sourced[1] - sourced[0];
        out.add(unsourced == 0 ? Check.pass("sources", "Every item is linked to a page where its words were found")
                : Check.warn("sources", unsourced + " of " + sourced[1] + " items have no source page where the words were found. Check them against the documents."));

        List<Double> weights = Content.list(s.assessments()).stream().map(Assessment::weight).filter(Objects::nonNull).toList();
        if (!weights.isEmpty()) {
            double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
            out.add(Math.abs(sum - 100) < 0.01 ? Check.pass("weights", "Assessment weights add up to 100%")
                    : Check.warn("weights", "Assessment weights add up to " + trim(sum) + "%, not 100%"));
        }
        if (Content.list(s.outcomes()).isEmpty()) out.add(Check.warn("outcomes", "No learning outcomes listed"));
        return out;
    }

    static int countNodes(List<Node> nodes) {
        int n = 0;
        for (Node x : nodes) n += 1 + countNodes(Content.list(x.children()));
        return n;
    }

    static void forEachSourced(Syllabus s, Consumer<List<Source>> f) {
        walk(Content.list(s.structure()), f);
        Content.list(s.outcomes()).forEach(o -> f.accept(Content.list(o.sources())));
        Content.list(s.assessments()).forEach(a -> f.accept(Content.list(a.sources())));
    }

    private static void walk(List<Node> nodes, Consumer<List<Source>> f) {
        for (Node n : nodes) {
            f.accept(Content.list(n.sources()));
            walk(Content.list(n.children()), f);
        }
    }

    // ---- what changed ------------------------------------------------------------------------------

    @Override
    public List<String> diff(JsonNode before, JsonNode after) {
        return diff(Content.read(before, Syllabus.class, "syllabus"), Content.read(after, Syllabus.class, "syllabus"));
    }

    static List<String> diff(Syllabus a, Syllabus b) {
        List<String> out = new ArrayList<>();
        Map<String, String> was = titles(Content.list(a.structure())), now = titles(Content.list(b.structure()));
        now.forEach((id, t) -> {
            if (!was.containsKey(id)) out.add("Added “" + t + "”");
            else if (!was.get(id).equals(t)) out.add("Renamed “" + was.get(id) + "” to “" + t + "”");
        });
        was.forEach((id, t) -> {
            if (!now.containsKey(id)) out.add("Removed “" + t + "”");
        });
        if (!Objects.equals(order(Content.list(a.structure())), order(Content.list(b.structure()))) && out.isEmpty()) {
            out.add("Reordered the structure");
        }
        count(out, "learning outcome", Content.list(a.outcomes()), Content.list(b.outcomes()));
        count(out, "assessment", Content.list(a.assessments()), Content.list(b.assessments()));
        Map<String, String> resA = new LinkedHashMap<>();
        Content.list(a.contradictions()).forEach(c -> resA.put(c.id(), Objects.toString(c.resolution(), "")));
        for (Contradiction c : Content.list(b.contradictions())) {
            String r = Objects.toString(c.resolution(), "");
            if (!r.equals(resA.getOrDefault(c.id(), ""))) out.add("Resolution changed: " + shorten(c.description()));
        }
        if (!Objects.equals(a.summary(), b.summary())) out.add("Summary changed");
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    private static Map<String, String> titles(List<Node> nodes) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Node n : nodes) {
            m.put(n.id(), n.title());
            m.putAll(titles(Content.list(n.children())));
        }
        return m;
    }

    private static List<String> order(List<Node> nodes) {
        List<String> out = new ArrayList<>();
        for (Node n : nodes) {
            out.add(n.id());
            out.add("(");
            out.addAll(order(Content.list(n.children())));
            out.add(")");
        }
        return out;
    }

    private static <T> void count(List<String> out, String what, List<T> a, List<T> b) {
        if (a.equals(b)) return;
        int diff = b.size() - a.size();
        if (diff > 0) out.add("Added " + diff + " " + what + (diff == 1 ? "" : "s"));
        else if (diff < 0) out.add("Removed " + (-diff) + " " + what + (diff == -1 ? "" : "s"));
        else out.add("Changed the " + what + "s");
    }

    static String shorten(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "…";
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    static Syllabus parse(JsonNode content) {
        return Content.read(content, Syllabus.class, "syllabus");
    }
}
