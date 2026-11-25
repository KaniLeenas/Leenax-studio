package com.leenax.studio.topicanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.topicanalysis.TopicAnalysis.Concept;
import com.leenax.studio.topicanalysis.TopicAnalysis.Difficulty;
import com.leenax.studio.topicanalysis.TopicAnalysis.Example;
import com.leenax.studio.topicanalysis.TopicAnalysis.Item;
import com.leenax.studio.topicanalysis.TopicAnalysis.McqPlan;
import com.leenax.studio.topicanalysis.TopicAnalysis.Misconception;
import com.leenax.studio.topicanalysis.TopicAnalysis.Note;
import com.leenax.studio.topicanalysis.TopicAnalysis.Objective;
import com.leenax.studio.topicanalysis.TopicAnalysis.Scene;
import com.leenax.studio.topicanalysis.TopicAnalysis.Source;
import com.leenax.studio.topicanalysis.TopicAnalysis.VideoPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a topic analysis may contain, and the checks before it is approved (E11). Text a person
 * saves is never cut or changed: anything that doesn't fit is refused with the reason. The AI's
 * answer is tidied once, before it is saved (unknown labels dropped, missing ids numbered).
 */
@Component
public class TopicAnalysisRules implements Review.ContentHandler {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int TEXT = 2000, TITLE = 300, LIST = 30, SCENES = 12;

    @Override
    public Kind kind() {
        return Kind.TOPIC_ANALYSIS;
    }

    // ---- saving --------------------------------------------------------------------------------------

    @Override
    public JsonNode normalise(JsonNode raw, UUID dependsOn) {
        if (dependsOn == null) throw ApiException.badRequest("A topic analysis is made from an approved topic list");
        TopicAnalysis a;
        try {
            a = JSON.treeToValue(raw, TopicAnalysis.class);
        } catch (Exception e) {
            throw ApiException.badRequest("This isn't a topic analysis");
        }
        return JSON.valueToTree(clean(a, dependsOn));
    }

    static TopicAnalysis clean(TopicAnalysis a, UUID dependsOn) {
        String topicId = required(a.topicId(), 64, "The topic");
        String topicTitle = required(a.topicTitle(), TITLE, "The topic's name");
        Difficulty d = a.difficulty();
        if (d != null && d.level() != null && !Learning.DIFFICULTIES.contains(d.level())) throw ApiException.badRequest("Difficulty must be beginner, intermediate or advanced");
        if (d != null) d = new Difficulty(d.level(), text(d.why(), TEXT, "Why it has that difficulty"));
        Set<String> oIds = new HashSet<>(), cIds = new HashSet<>();
        List<Objective> objectives = new ArrayList<>();
        for (Objective o : list(a.objectives(), "objectives")) {
            String id = id(o.id(), oIds, "An objective");
            if (o.bloom() != null && !TopicAnalysis.BLOOM.contains(o.bloom())) throw ApiException.badRequest("Unknown Bloom level: " + o.bloom());
            objectives.add(new Objective(id, required(o.text(), TEXT, "An objective"), o.bloom(), strings(o.outcomes(), 20, "outcome code"), sources(o.sources())));
        }
        List<Concept> concepts = new ArrayList<>();
        for (Concept c : list(a.concepts(), "concepts")) {
            String id = id(c.id(), cIds, "A concept");
            concepts.add(new Concept(id, required(c.term(), TITLE, "A concept's term"), text(c.definition(), TEXT, "A definition"), basis(c.basis()),
                    sources(c.sources())));
        }
        List<Misconception> mis = new ArrayList<>();
        int n = 0;
        for (Misconception m : list(a.misconceptions(), "misconceptions")) {
            String concept = m.concept() == null || m.concept().isBlank() ? null : m.concept().strip();
            if (concept != null && !cIds.contains(concept)) throw ApiException.badRequest("A misconception names a concept that isn't in the list: " + concept);
            mis.add(new Misconception(++n, required(m.wrong(), TEXT, "A misconception"), text(m.right(), TEXT, "The correct belief"), concept, basis(m.basis())));
        }
        List<Example> examples = new ArrayList<>();
        for (Example e : list(a.examples(), "examples")) {
            if (e.kind() != null && !TopicAnalysis.EXAMPLE_KINDS.contains(e.kind())) throw ApiException.badRequest("Unknown example kind: " + e.kind());
            examples.add(new Example(required(e.title(), TITLE, "An example"), e.kind(), refs(e.concepts(), cIds, "An example"), sources(e.sources())));
        }
        List<McqPlan> plan = new ArrayList<>();
        Set<String> planned = new HashSet<>();
        for (McqPlan p : list(a.mcqPlan(), "question plan rows")) {
            String o = p.objective() == null ? null : p.objective().strip();
            if (o == null || !oIds.contains(o)) throw ApiException.badRequest("The question plan names an objective that isn't in the list");
            if (!planned.add(o)) throw ApiException.badRequest("The question plan lists " + o + " twice");
            int count = p.count() == null ? 0 : p.count();
            if (count < 0 || count > 30) throw ApiException.badRequest("Questions per objective must be 0 to 30");
            Map<String, Integer> diff = new LinkedHashMap<>();
            if (p.difficulties() != null) {
                for (Map.Entry<String, Integer> e : p.difficulties().entrySet()) {
                    if (!Learning.DIFFICULTIES.contains(e.getKey())) throw ApiException.badRequest("Unknown difficulty: " + e.getKey());
                    int v = e.getValue() == null ? 0 : e.getValue();
                    if (v < 0 || v > 30) throw ApiException.badRequest("A difficulty count must be 0 to 30");
                    if (v > 0) diff.put(e.getKey(), v);
                }
            }
            List<String> styles = new ArrayList<>();
            for (String s : list(p.styles(), "styles")) {
                if (!Learning.STYLES.contains(s)) throw ApiException.badRequest("Unknown question style: " + s);
                if (!styles.contains(s)) styles.add(s);
            }
            plan.add(new McqPlan(o, count, diff, styles));
        }
        VideoPlan v = a.videoPlan();
        List<Scene> scenes = new ArrayList<>();
        if (v != null) {
            for (Scene s : list(v.scenes(), "scenes")) {
                if (s.shows() != null && !TopicAnalysis.SHOWS.contains(s.shows())) throw ApiException.badRequest("Unknown scene type: " + s.shows());
                String goal = s.goal() == null || s.goal().isBlank() ? null : s.goal().strip();
                if (goal != null && !oIds.contains(goal)) throw ApiException.badRequest("A scene's goal isn't one of the objectives: " + goal);
                int sec = s.seconds() == null ? 0 : s.seconds();
                if (sec < 5 || sec > 900) throw ApiException.badRequest("A scene runs 5 to 900 seconds");
                scenes.add(new Scene(required(s.title(), TITLE, "A scene's title"), goal, s.shows(), sec, refs(s.concepts(), cIds, "A scene")));
            }
            if (scenes.size() > SCENES) throw ApiException.badRequest("A video plan has at most " + SCENES + " scenes");
        }
        Integer mcqCount = a.mcqCount(), minutes = a.videoMinutes();
        if (mcqCount != null && (mcqCount < 1 || mcqCount > 30)) throw ApiException.badRequest("The number of questions must be 1 to 30");
        if (minutes != null && (minutes < 1 || minutes > 30)) throw ApiException.badRequest("The video length must be 1 to 30 minutes");
        return new TopicAnalysis(topicId, topicTitle, text(a.topicPath(), 600, "The topic's place"), dependsOn, text(a.summary(), 4000, "The summary"), d,
                items(a.prerequisites(), "prerequisites"), objectives, concepts, mis, examples, items(a.examRelevance(), "exam relevance"), mcqCount, plan,
                minutes, v == null ? null : new VideoPlan(text(v.hook(), TEXT, "The hook"), scenes), notes(a.gaps(), "gaps"),
                notes(a.questions(), "questions"), a.analysis());
    }

    /**
     * The AI's answer made saveable without guessing at meaning: labels it doesn't know are
     * dropped, missing or repeated ids are numbered, references to unknown items are left out,
     * and over-long lists are trimmed to the limit. What was dropped is counted for the job summary.
     */
    static TopicAnalysis tidy(TopicAnalysis a, List<String> notes) {
        Set<String> oIds = new HashSet<>(), cIds = new HashSet<>();
        List<Objective> objectives = new ArrayList<>();
        for (Objective o : cap(a.objectives())) {
            String id = freeId(o.id(), "O", oIds);
            List<String> outcomes = o.outcomes() == null ? List.of() : o.outcomes().stream().filter(x -> x != null && !x.isBlank() && x.strip().length() <= 20)
                    .map(String::strip).limit(20).toList();
            objectives.add(new Objective(id, clip(o.text(), TEXT, notes), has(TopicAnalysis.BLOOM, o.bloom()) ? o.bloom() : null, outcomes, o.sources()));
        }
        List<Concept> concepts = new ArrayList<>();
        for (Concept c : cap(a.concepts())) {
            String id = freeId(c.id(), "C", cIds);
            concepts.add(new Concept(id, clip(c.term(), TITLE, notes), clip(c.definition(), TEXT, notes), tidyBasis(c.basis(), c.sources()), c.sources()));
        }
        List<Misconception> mis = new ArrayList<>();
        for (Misconception m : cap(a.misconceptions())) {
            mis.add(new Misconception(m.number(), clip(m.wrong(), TEXT, notes), clip(m.right(), TEXT, notes), cIds.contains(m.concept()) ? m.concept() : null,
                    has(TopicAnalysis.BASIS, m.basis()) ? m.basis() : "GENERAL"));
        }
        List<Example> examples = new ArrayList<>();
        for (Example e : cap(a.examples())) {
            examples.add(new Example(clip(e.title(), TITLE, notes), has(TopicAnalysis.EXAMPLE_KINDS, e.kind()) ? e.kind() : null,
                    e.concepts().stream().filter(cIds::contains).toList(), e.sources()));
        }
        List<McqPlan> plan = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (McqPlan p : cap(a.mcqPlan())) {
            if (p.objective() == null || !oIds.contains(p.objective()) || !seen.add(p.objective())) {
                notes.add("a question plan row for an unknown or repeated objective was left out");
                continue;
            }
            Map<String, Integer> diff = new LinkedHashMap<>();
            p.difficulties().forEach((k, v) -> {
                if (has(Learning.DIFFICULTIES, k) && v != null && v > 0) diff.put(k, Math.min(30, v));
            });
            plan.add(new McqPlan(p.objective(), p.count() == null ? 0 : Math.max(0, Math.min(30, p.count())), diff,
                    p.styles().stream().filter(Learning.STYLES::contains).distinct().toList()));
        }
        VideoPlan v = a.videoPlan();
        List<Scene> scenes = new ArrayList<>();
        if (v != null) {
            for (Scene s : v.scenes()) {
                if (scenes.size() >= SCENES) {
                    notes.add("scenes after the " + SCENES + "th were left out");
                    break;
                }
                int sec = s.seconds() == null ? 60 : Math.max(5, Math.min(900, s.seconds()));
                scenes.add(new Scene(clip(s.title(), TITLE, notes), oIds.contains(s.goal()) ? s.goal() : null,
                        has(TopicAnalysis.SHOWS, s.shows()) ? s.shows() : "CONCEPT", sec, s.concepts().stream().filter(cIds::contains).toList()));
            }
        }
        Difficulty d = a.difficulty() == null ? null
                : new Difficulty(has(Learning.DIFFICULTIES, a.difficulty().level()) ? a.difficulty().level() : null, clip(a.difficulty().why(), TEXT));
        return new TopicAnalysis(a.topicId(), clip(a.topicTitle(), TITLE, notes), clip(a.topicPath(), 600, notes), a.topicsVersionId(), clip(a.summary(), 4000, notes), d,
                tidyItems(a.prerequisites(), notes), objectives, concepts, mis, examples, tidyItems(a.examRelevance(), notes), a.mcqCount(), plan, a.videoMinutes(),
                v == null ? null : new VideoPlan(clip(v.hook(), TEXT, notes), scenes), cap(a.gaps()), cap(a.questions()), a.analysis());
    }

    /** The id the AI gave if it is usable and not taken, else the next free one (O1, O2, ...). */
    static String freeId(String given, String prefix, Set<String> taken) {
        String g = given == null ? "" : given.strip();
        if (g.matches("[A-Za-z0-9_-]{1,16}") && taken.add(g)) return g;
        int k = taken.size() + 1;
        while (!taken.add(prefix + k)) k++;
        return prefix + k;
    }

    private static List<Item> tidyItems(List<Item> in, List<String> notes) {
        return cap(in).stream().map(x -> new Item(clip(x.text(), TEXT, notes), tidyBasis(x.basis(), x.sources()), x.sources())).toList();
    }

    private static String tidyBasis(String basis, List<Source> sources) {
        if (has(TopicAnalysis.BASIS, basis)) return basis;
        return sources != null && !sources.isEmpty() ? "DOCUMENT" : "GENERAL";
    }

    // ---- checks before approval ------------------------------------------------------------------------

    @Override
    public List<Check> checks(JsonNode content, Review.Context ctx) {
        TopicAnalysis a = JSON.convertValue(content, TopicAnalysis.class);
        List<Check> out = new ArrayList<>();
        JsonNode topics = ctx.approvedContentOf().apply(Kind.TOPICS);
        boolean inList = topics != null && selected(topics.path("nodes"), a.topicId());
        out.add(inList ? Check.pass("topic", "The topic is in the approved topic list")
                : Check.fail("topic", "The topic isn't in the approved topic list any more"));
        int objectives = size(a.objectives());
        out.add(objectives > 0 ? Check.pass("objectives", objectives + " learning objective" + (objectives == 1 ? "" : "s"))
                : Check.fail("objectives", "Add at least one learning objective"));
        int planned = a.mcqPlan() == null ? 0 : a.mcqPlan().stream().mapToInt(p -> p.count() == null ? 0 : p.count()).sum();
        if (a.mcqCount() != null && planned != a.mcqCount()) {
            out.add(Check.fail("question-plan", "The question plan adds up to " + planned + ", not " + a.mcqCount()));
        } else if (planned == 0) {
            out.add(Check.fail("question-plan", "Plan at least one question"));
        } else {
            List<String> mismatched = new ArrayList<>();
            for (McqPlan p : a.mcqPlan()) {
                int split = p.difficulties() == null ? 0 : p.difficulties().values().stream().mapToInt(Integer::intValue).sum();
                if (split != 0 && split != (p.count() == null ? 0 : p.count())) mismatched.add(p.objective());
            }
            out.add(mismatched.isEmpty() ? Check.pass("question-plan", planned + " questions planned across the objectives")
                    : Check.warn("question-plan", "The difficulty split doesn't add up to the count for " + String.join(", ", mismatched)));
        }
        int scenes = a.videoPlan() == null ? 0 : size(a.videoPlan().scenes());
        if (scenes == 0) {
            out.add(Check.fail("video-plan", "Plan at least one scene for the lesson video"));
        } else {
            int secs = a.videoPlan().scenes().stream().mapToInt(s -> s.seconds() == null ? 0 : s.seconds()).sum();
            int target = (a.videoMinutes() == null ? 8 : a.videoMinutes()) * 60;
            boolean near = Math.abs(secs - target) <= target / 4;
            out.add(near ? Check.pass("video-plan", scenes + " scenes, " + clock(secs) + " in all")
                    : Check.warn("video-plan", "The scenes add up to " + clock(secs) + ", not about " + clock(target)));
        }
        int missing = 0, unfound = 0;
        for (Source s : allSources(a)) if (!Boolean.TRUE.equals(s.found())) unfound++;
        for (Concept c : list(a.concepts(), null)) if ("DOCUMENT".equals(c.basis()) && size(c.sources()) == 0) missing++;
        out.add(unfound == 0 && missing == 0 ? Check.pass("sources", "Every cited quote is on its page")
                : Check.warn("sources", (unfound > 0 ? unfound + " cited quote" + (unfound == 1 ? " isn't" : "s aren't") + " found on its page" : "")
                + (unfound > 0 && missing > 0 ? "; " : "") + (missing > 0 ? missing + " concept" + (missing == 1 ? "" : "s") + " marked as from the documents cite" + (missing == 1 ? "s" : "") + " nothing" : "")));
        int open = size(a.gaps()) + size(a.questions());
        out.add(open == 0 ? Check.pass("open-points", "No gaps or open questions")
                : Check.warn("open-points", size(a.gaps()) + " gap" + (size(a.gaps()) == 1 ? "" : "s") + " in the documents and " + size(a.questions())
                + " question" + (size(a.questions()) == 1 ? "" : "s") + " for the lecturer"));
        return out;
    }

    static boolean selected(JsonNode nodes, String id) {
        for (JsonNode n : nodes) {
            if (!n.path("selected").asBoolean(true)) continue;
            if (id != null && id.equals(n.path("id").asText())) return true;
            if (selected(n.path("children"), id)) return true;
        }
        return false;
    }

    static List<Source> allSources(TopicAnalysis a) {
        List<Source> out = new ArrayList<>();
        list(a.prerequisites(), null).forEach(x -> out.addAll(list(x.sources(), null)));
        list(a.objectives(), null).forEach(x -> out.addAll(list(x.sources(), null)));
        list(a.concepts(), null).forEach(x -> out.addAll(list(x.sources(), null)));
        list(a.examples(), null).forEach(x -> out.addAll(list(x.sources(), null)));
        list(a.examRelevance(), null).forEach(x -> out.addAll(list(x.sources(), null)));
        return out;
    }

    @Override
    public List<String> diff(JsonNode before, JsonNode after) {
        TopicAnalysis a = JSON.convertValue(before, TopicAnalysis.class), b = JSON.convertValue(after, TopicAnalysis.class);
        List<String> out = new ArrayList<>();
        compare(out, "objectives", size(a.objectives()), size(b.objectives()));
        compare(out, "concepts", size(a.concepts()), size(b.concepts()));
        compare(out, "misconceptions", size(a.misconceptions()), size(b.misconceptions()));
        compare(out, "scenes", a.videoPlan() == null ? 0 : size(a.videoPlan().scenes()), b.videoPlan() == null ? 0 : size(b.videoPlan().scenes()));
        if (!JSON.valueToTree(list(a.mcqPlan(), null)).equals(JSON.valueToTree(list(b.mcqPlan(), null)))) out.add("question plan changed");
        if (!JSON.valueToTree(a.objectives() == null ? List.of() : a.objectives()).equals(JSON.valueToTree(b.objectives() == null ? List.of() : b.objectives()))
                && size(a.objectives()) == size(b.objectives())) out.add("objectives reworded");
        if (out.isEmpty() && !JSON.valueToTree(a).equals(JSON.valueToTree(b))) out.add("text edited");
        return out;
    }

    private static void compare(List<String> out, String what, int a, int b) {
        if (a != b) out.add(what + ": " + a + " → " + b);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static List<Item> items(List<Item> in, String what) {
        List<Item> out = new ArrayList<>();
        for (Item i : list(in, what)) out.add(new Item(required(i.text(), TEXT, "An item"), basis(i.basis()), sources(i.sources())));
        return out;
    }

    private static List<Note> notes(List<Note> in, String what) {
        List<Note> out = new ArrayList<>();
        for (Note x : list(in, what)) out.add(new Note(required(x.text(), TEXT, "A note")));
        return out;
    }

    private static List<Source> sources(List<Source> in) {
        return in == null ? List.of() : in.stream().filter(s -> s != null && s.page() != null).limit(10).toList();
    }

    private static String basis(String b) {
        if (b == null) return "GENERAL";
        if (!TopicAnalysis.BASIS.contains(b)) throw ApiException.badRequest("Basis must be DOCUMENT or GENERAL");
        return b;
    }

    private static List<String> refs(List<String> in, Set<String> known, String what) {
        List<String> out = new ArrayList<>();
        for (String r : list(in, null)) {
            if (!known.contains(r)) throw ApiException.badRequest(what + " names a concept that isn't in the list: " + r);
            if (!out.contains(r)) out.add(r);
        }
        return out;
    }

    private static List<String> strings(List<String> in, int max, String what) {
        List<String> out = new ArrayList<>();
        for (String s : list(in, null)) {
            if (s == null || s.isBlank()) continue;
            if (s.length() > max) throw ApiException.badRequest("An " + what + " is too long");
            out.add(s.strip());
        }
        return out;
    }

    private static String id(String id, Set<String> seen, String what) {
        String s = required(id, 16, what + "'s id");
        if (!s.matches("[A-Za-z0-9_-]{1,16}")) throw ApiException.badRequest(what + "'s id can use letters, digits, - and _");
        if (!seen.add(s)) throw ApiException.badRequest(what + "'s id " + s + " is used twice");
        return s;
    }

    static <T> List<T> list(List<T> in, String what) {
        if (in == null) return List.of();
        if (what != null && in.size() > LIST && !what.equals("scenes")) throw ApiException.badRequest("At most " + LIST + " " + what);
        return in.stream().filter(x -> x != null).toList();
    }

    static <T> List<T> cap(List<T> in) {
        return in == null ? List.of() : in.stream().filter(x -> x != null).limit(LIST).toList();
    }

    static String required(String s, int max, String what) {
        if (s == null || s.isBlank()) throw ApiException.badRequest(what + " is empty");
        if (s.strip().length() > max) throw ApiException.badRequest(what + " is longer than " + max + " characters");
        return s.strip();
    }

    static String text(String s, int max, String what) {
        if (s == null || s.isBlank()) return null;
        if (s.strip().length() > max) throw ApiException.badRequest(what + " is longer than " + max + " characters");
        return s.strip();
    }

    /** Shortens an over-long AI answer once, before anyone sees it, and says so in the job's summary. */
    static String clip(String s, int max, List<String> notes) {
        if (s != null && s.strip().length() > max) notes.add("a long item was shortened to " + max + " characters");
        return clip(s, max);
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Set.of(...).contains(null) throws; the AI's answer can leave any label out. */
    static boolean has(java.util.Collection<String> set, String value) {
        return value != null && set.contains(value);
    }

    static int size(List<?> l) {
        return l == null ? 0 : l.size();
    }

    static String clock(int seconds) {
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
