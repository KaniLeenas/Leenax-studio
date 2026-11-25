package com.leenax.studio.syllabus;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.syllabus.Content.Node;
import com.leenax.studio.syllabus.Content.Syllabus;
import com.leenax.studio.syllabus.Content.Topic;
import com.leenax.studio.syllabus.Content.Topics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.leenax.studio.syllabus.Content.TITLE_MAX;

/**
 * What a topic list is allowed to be. Topics are built only from an approved syllabus,
 * and can be approved only while that syllabus version is still the approved one.
 */
@Component
public class TopicRules implements Review.ContentHandler {

    @Override
    public Kind kind() {
        return Kind.TOPICS;
    }

    @Override
    public JsonNode normalise(JsonNode raw, UUID dependsOn) {
        if (dependsOn == null) throw ApiException.badRequest("A topic list is built from an approved syllabus");
        Topics t = Content.read(raw, Topics.class, "topic list");
        return Content.tree(clean(new Topics(dependsOn, t.nodes())));
    }

    static Topics clean(Topics t) {
        Set<String> ids = new HashSet<>();
        int[] count = {0};
        List<Topic> nodes = topics(Content.list(t.nodes()), 1, ids, count);
        if (count[0] > Content.MAX_ITEMS) throw ApiException.badRequest("A topic list can have at most " + Content.MAX_ITEMS + " topics");
        Map<String, String> titles = titles(nodes);
        // Prerequisites must name other topics in this list.
        for (Topic n : flatten(nodes)) {
            for (String p : Content.list(n.prerequisites())) {
                if (p.equals(n.id())) throw ApiException.badRequest("“" + n.title() + "” can't be its own prerequisite");
                if (!titles.containsKey(p)) throw ApiException.badRequest("A prerequisite of “" + n.title() + "” isn't in the list");
            }
        }
        return new Topics(t.syllabusVersionId(), nodes);
    }

    private static List<Topic> topics(List<Topic> in, int depth, Set<String> ids, int[] count) {
        List<Topic> out = new ArrayList<>();
        for (Topic n : in) {
            if (n == null) continue;
            String title = Content.required(n.title(), TITLE_MAX, "A topic name");
            List<Topic> kids = Content.list(n.children());
            if (depth >= Content.MAX_DEPTH && !kids.isEmpty()) {
                throw ApiException.badRequest("“" + title + "” is a subtopic and can't have topics under it");
            }
            count[0]++;
            String difficulty = n.difficulty() == null || n.difficulty().isBlank() ? null : n.difficulty().strip();
            if (difficulty != null && !Content.DIFFICULTIES.contains(difficulty)) {
                throw ApiException.badRequest("Difficulty must be introductory, intermediate or advanced");
            }
            String id = Content.id(n.id(), ids);
            out.add(new Topic(id, title, distinct(n.syllabusItemIds()), n.selected() == null || n.selected(), difficulty,
                    distinct(n.prerequisites()), Content.sources(n.sources()), Boolean.TRUE.equals(n.notInSyllabus()) ? Boolean.TRUE : null,
                    depth >= Content.MAX_DEPTH ? List.of() : topics(kids, depth + 1, ids, count)));
        }
        return out;
    }

    // ---- checks ------------------------------------------------------------------------------------

    @Override
    public List<Check> checks(JsonNode content, Context ctx) {
        return checks(Content.read(content, Topics.class, "topic list"), ctx.approvedVersionOf().apply(Kind.SYLLABUS));
    }

    static List<Check> checks(Topics t, UUID approvedSyllabus) {
        List<Check> out = new ArrayList<>();
        if (approvedSyllabus == null) {
            out.add(Check.fail("syllabus", "The syllabus isn't approved. Topics can be approved only from an approved syllabus."));
        } else if (!approvedSyllabus.equals(t.syllabusVersionId())) {
            out.add(Check.fail("syllabus", "The approved syllabus has changed since this list was made. Update it from the approved syllabus."));
        } else {
            out.add(Check.pass("syllabus", "Built from the approved syllabus"));
        }
        List<Topic> all = flatten(Content.list(t.nodes()));
        List<Topic> chosen = all.stream().filter(n -> Boolean.TRUE.equals(n.selected())).toList();
        out.add(chosen.isEmpty() ? Check.fail("selected", "Select at least one topic")
                : Check.pass("selected", chosen.size() + " of " + all.size() + " topics selected"));

        List<String> gone = chosen.stream().filter(n -> Boolean.TRUE.equals(n.notInSyllabus())).map(Topic::title).toList();
        if (!gone.isEmpty()) {
            out.add(Check.fail("notInSyllabus", "Selected but no longer in the syllabus: " + names(gone) + ". Remove or unselect them."));
        }

        Set<String> chosenIds = new HashSet<>();
        chosen.forEach(n -> chosenIds.add(n.id()));
        List<String> unselectedPrereq = new ArrayList<>();
        for (Topic n : chosen) {
            for (String p : Content.list(n.prerequisites())) {
                if (!chosenIds.contains(p)) unselectedPrereq.add(n.title());
            }
        }
        if (!unselectedPrereq.isEmpty()) {
            out.add(Check.fail("prerequisites", "These need a topic that isn't selected: " + names(unselectedPrereq)));
        }
        List<String> cycle = cycle(chosen);
        out.add(cycle.isEmpty() ? Check.pass("order", "Prerequisites have no loops")
                : Check.fail("order", "Prerequisites go round in a loop: " + String.join(" → ", cycle)));

        long noLevel = chosen.stream().filter(n -> n.difficulty() == null).count();
        if (noLevel > 0) out.add(Check.warn("difficulty", noLevel + " selected topics have no difficulty set"));
        return out;
    }

    /** A loop among prerequisites, as titles (first repeated at the end), or empty. */
    static List<String> cycle(List<Topic> topics) {
        Map<String, Topic> byId = new LinkedHashMap<>();
        topics.forEach(n -> byId.put(n.id(), n));
        Map<String, Integer> state = new HashMap<>();
        for (String start : byId.keySet()) {
            List<String> path = new ArrayList<>();
            List<String> found = visit(start, byId, state, path);
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    private static List<String> visit(String id, Map<String, Topic> byId, Map<String, Integer> state, List<String> path) {
        Integer s = state.get(id);
        if (s != null && s == 2) return List.of();
        if (s != null && s == 1) {
            List<String> loop = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
            loop.add(id);
            return loop.stream().map(x -> byId.get(x).title()).toList();
        }
        Topic n = byId.get(id);
        if (n == null) return List.of();
        state.put(id, 1);
        path.add(id);
        for (String p : Content.list(n.prerequisites())) {
            if (!byId.containsKey(p)) continue;
            List<String> found = visit(p, byId, state, path);
            if (!found.isEmpty()) return found;
        }
        path.remove(path.size() - 1);
        state.put(id, 2);
        return List.of();
    }

    // ---- building from the syllabus ----------------------------------------------------------------

    /**
     * Brings a topic list up to date with an approved syllabus. Your edits stay: names,
     * order, merges, selection, difficulty and prerequisites. Syllabus items not yet in the
     * list are added under the topic that holds their parent item (or at the end). Topics
     * whose syllabus items are all gone are marked "not in the syllabus". Sources follow
     * the syllabus. With no list yet, the result mirrors the syllabus.
     */
    public static Topics rebase(Topics existing, Syllabus syllabus, UUID syllabusVersionId) {
        List<Topic> nodes = existing == null ? new ArrayList<>() : copy(Content.list(existing.nodes()));
        Map<String, Node> items = new LinkedHashMap<>();
        Map<String, String> parentOf = new HashMap<>();
        index(Content.list(syllabus.structure()), null, items, parentOf);

        nodes = refresh(nodes, items);
        Set<String> covered = new HashSet<>();
        flatten(nodes).forEach(n -> covered.addAll(Content.list(n.syllabusItemIds())));

        for (Map.Entry<String, Node> e : items.entrySet()) {
            if (covered.contains(e.getKey())) continue;
            Node item = e.getValue();
            Topic fresh = new Topic(Content.newId(), item.title(), List.of(item.id()), true, null, List.of(),
                    Content.list(item.sources()), null, new ArrayList<>());
            String parentItem = parentOf.get(item.id());
            if (parentItem == null || !insertUnder(nodes, parentItem, fresh, 1)) nodes.add(fresh);
            covered.add(item.id());
        }
        return new Topics(syllabusVersionId, nodes);
    }

    private static void index(List<Node> nodes, String parent, Map<String, Node> items, Map<String, String> parentOf) {
        for (Node n : nodes) {
            items.put(n.id(), n);
            if (parent != null) parentOf.put(n.id(), parent);
            index(Content.list(n.children()), n.id(), items, parentOf);
        }
    }

    private static List<Topic> refresh(List<Topic> nodes, Map<String, Node> items) {
        List<Topic> out = new ArrayList<>();
        for (Topic n : nodes) {
            List<String> ids = Content.list(n.syllabusItemIds());
            List<String> still = ids.stream().filter(items::containsKey).toList();
            boolean gone = !ids.isEmpty() && still.isEmpty();
            List<Content.Source> sources = still.isEmpty() ? Content.list(n.sources())
                    : still.stream().flatMap(id -> Content.list(items.get(id).sources()).stream()).distinct().toList();
            out.add(new Topic(n.id(), n.title(), ids, n.selected(), n.difficulty(), Content.list(n.prerequisites()), sources,
                    gone ? Boolean.TRUE : null, refresh(Content.list(n.children()), items)));
        }
        return out;
    }

    /** Adds {@code fresh} under the topic covering {@code parentItem}, if that topic may have children. */
    private static boolean insertUnder(List<Topic> nodes, String parentItem, Topic fresh, int depth) {
        for (int i = 0; i < nodes.size(); i++) {
            Topic n = nodes.get(i);
            if (Content.list(n.syllabusItemIds()).contains(parentItem) && depth < Content.MAX_DEPTH) {
                List<Topic> kids = new ArrayList<>(Content.list(n.children()));
                kids.add(fresh);
                nodes.set(i, with(n, kids));
                return true;
            }
            List<Topic> kids = new ArrayList<>(Content.list(n.children()));
            if (insertUnder(kids, parentItem, fresh, depth + 1)) {
                nodes.set(i, with(n, kids));
                return true;
            }
        }
        return false;
    }

    private static Topic with(Topic n, List<Topic> children) {
        return new Topic(n.id(), n.title(), n.syllabusItemIds(), n.selected(), n.difficulty(), n.prerequisites(), n.sources(),
                n.notInSyllabus(), children);
    }

    private static List<Topic> copy(List<Topic> nodes) {
        List<Topic> out = new ArrayList<>();
        for (Topic n : nodes) out.add(with(n, copy(Content.list(n.children()))));
        return out;
    }

    // ---- what changed ------------------------------------------------------------------------------

    @Override
    public List<String> diff(JsonNode before, JsonNode after) {
        return diff(Content.read(before, Topics.class, "topic list"), Content.read(after, Topics.class, "topic list"));
    }

    static List<String> diff(Topics a, Topics b) {
        Map<String, Topic> was = byId(flatten(Content.list(a.nodes()))), now = byId(flatten(Content.list(b.nodes())));
        List<String> out = new ArrayList<>();
        now.forEach((id, n) -> {
            Topic o = was.get(id);
            if (o == null) {
                out.add("Added “" + n.title() + "”");
                return;
            }
            if (!o.title().equals(n.title())) out.add("Renamed “" + o.title() + "” to “" + n.title() + "”");
            if (!Objects.equals(o.selected(), n.selected())) out.add((Boolean.TRUE.equals(n.selected()) ? "Selected “" : "Unselected “") + n.title() + "”");
            if (!Objects.equals(o.difficulty(), n.difficulty())) out.add("Difficulty of “" + n.title() + "”: " + level(n.difficulty()));
            if (!new HashSet<>(Content.list(o.prerequisites())).equals(new HashSet<>(Content.list(n.prerequisites())))) {
                out.add("Prerequisites of “" + n.title() + "” changed");
            }
        });
        was.forEach((id, o) -> {
            if (!now.containsKey(id)) out.add("Removed “" + o.title() + "”");
        });
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    private static String level(String d) {
        return d == null ? "not set" : d.toLowerCase(java.util.Locale.ROOT);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    static List<Topic> flatten(List<Topic> nodes) {
        List<Topic> out = new ArrayList<>();
        for (Topic n : nodes) {
            out.add(n);
            out.addAll(flatten(Content.list(n.children())));
        }
        return out;
    }

    private static Map<String, Topic> byId(List<Topic> all) {
        Map<String, Topic> m = new LinkedHashMap<>();
        all.forEach(n -> m.put(n.id(), n));
        return m;
    }

    private static Map<String, String> titles(List<Topic> nodes) {
        Map<String, String> m = new HashMap<>();
        flatten(nodes).forEach(n -> m.put(n.id(), n.title()));
        return m;
    }

    private static List<String> distinct(List<String> in) {
        if (in == null) return List.of();
        return List.copyOf(new LinkedHashSet<>(in.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList()));
    }

    private static String names(List<String> titles) {
        List<String> d = List.copyOf(new LinkedHashSet<>(titles));
        String s = String.join(", ", d.subList(0, Math.min(5, d.size())));
        return d.size() > 5 ? s + " and " + (d.size() - 5) + " more" : s;
    }

    static Topics parse(JsonNode content) {
        return Content.read(content, Topics.class, "topic list");
    }
}
