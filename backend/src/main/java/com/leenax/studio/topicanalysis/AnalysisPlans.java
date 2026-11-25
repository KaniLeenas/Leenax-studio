package com.leenax.studio.topicanalysis;

import com.leenax.studio.topicanalysis.TopicAnalysis.Concept;
import com.leenax.studio.topicanalysis.TopicAnalysis.McqPlan;
import com.leenax.studio.topicanalysis.TopicAnalysis.Misconception;
import com.leenax.studio.topicanalysis.TopicAnalysis.Objective;
import com.leenax.studio.topicanalysis.TopicAnalysis.Scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * How an approved topic analysis steers the question prompt (Prompt 2) and the presentation
 * prompt (Prompt 3). The plan is followed, not enforced: what the AI wrote is still checked by
 * the question and slide rules, and people review it as before.
 */
public final class AnalysisPlans {

    private AnalysisPlans() {
    }

    /** Speaking pace for the video script: about 150 words a minute in English, 120 in Tamil. */
    public static int wordsPerMinute(String text) {
        if (text == null) return 150;
        long tamil = text.codePoints().filter(c -> c >= 0x0B80 && c <= 0x0BFF).count();
        long letters = text.codePoints().filter(Character::isLetter).count();
        return letters > 0 && tamil * 3 >= letters ? 120 : 150;
    }

    /** The plan's counts scaled to the number asked for, keeping every planned objective that fits. */
    public static Map<String, Integer> scale(List<McqPlan> plan, int count) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int total = plan.stream().mapToInt(p -> p.count() == null ? 0 : p.count()).sum();
        if (total <= 0 || count <= 0) return out;
        record Share(String objective, int whole, double rest) {
        }
        List<Share> shares = new ArrayList<>();
        int given = 0;
        for (McqPlan p : plan) {
            int c = p.count() == null ? 0 : p.count();
            if (c <= 0) continue;
            double exact = (double) c * count / total;
            int whole = (int) Math.floor(exact);
            shares.add(new Share(p.objective(), whole, exact - whole));
            given += whole;
        }
        List<Share> byRest = new ArrayList<>(shares);
        byRest.sort(Comparator.comparingDouble(Share::rest).reversed());
        Map<String, Integer> extra = new LinkedHashMap<>();
        for (int i = 0; i < count - given && i < byRest.size(); i++) extra.merge(byRest.get(i).objective(), 1, Integer::sum);
        for (Share s : shares) {
            int n = s.whole() + extra.getOrDefault(s.objective(), 0);
            if (n > 0) out.put(s.objective(), n);
        }
        return out;
    }

    /** The question plan as a section of the question prompt. */
    public static String questionPlan(TopicAnalysis a, int count) {
        Map<String, Integer> counts = scale(a.mcqPlan() == null ? List.of() : a.mcqPlan(), count);
        if (counts.isEmpty()) return "";
        Map<String, Objective> objectives = a.objectives().stream().collect(Collectors.toMap(Objective::id, o -> o, (x, y) -> x, LinkedHashMap::new));
        Map<String, Concept> concepts = a.concepts() == null ? Map.of()
                : a.concepts().stream().collect(Collectors.toMap(Concept::id, c -> c, (x, y) -> x));
        StringBuilder sb = new StringBuilder("\nFollow this plan from the approved topic analysis. Write the number of questions shown for each ")
                .append("objective, test that objective, and put its id in \"objective\".\n");
        for (McqPlan p : a.mcqPlan()) {
            Integer n = counts.get(p.objective());
            Objective o = objectives.get(p.objective());
            if (n == null || o == null) continue;
            sb.append("- ").append(o.id()).append(" (").append(n).append(n == 1 ? " question" : " questions");
            if (o.bloom() != null) sb.append(", ").append(o.bloom().toLowerCase(java.util.Locale.ROOT));
            sb.append("): ").append(oneLine(o.text()));
            if (p.difficulties() != null && !p.difficulties().isEmpty()) {
                sb.append(". Difficulty: ").append(p.difficulties().entrySet().stream().map(e -> e.getValue() + " " + e.getKey().toLowerCase(java.util.Locale.ROOT))
                        .collect(Collectors.joining(", "))).append(" (scale to the count)");
            }
            if (p.styles() != null && !p.styles().isEmpty()) sb.append(". Styles: ").append(String.join(", ", p.styles()));
            sb.append('\n');
        }
        List<Misconception> mis = a.misconceptions() == null ? List.of() : a.misconceptions();
        if (!mis.isEmpty()) {
            sb.append("\nCommon misconceptions. Use them as wrong options where they fit, and say in that option's explanation why it is wrong:\n");
            for (Misconception m : mis) {
                sb.append("- ").append(oneLine(m.wrong()));
                if (m.right() != null) sb.append(" (correct: ").append(oneLine(m.right())).append(')');
                Concept c = m.concept() == null ? null : concepts.get(m.concept());
                if (c != null) sb.append(" [").append(c.term()).append(']');
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    static final Pattern TAG = Pattern.compile("^([A-Za-z0-9_-]{1,16}): ");

    /** The objective a planned question was written for, from its subtopic ("O2: ..."). */
    public static String objectiveOf(String subtopic, Set<String> known) {
        if (subtopic == null) return null;
        Matcher m = TAG.matcher(subtopic);
        return m.find() && known.contains(m.group(1)) ? m.group(1) : null;
    }

    /** How a bank covers the plan, for the job's summary: "O1 4/4, O2 3/5". */
    public static String coverage(TopicAnalysis a, int count, List<String> subtopics) {
        Map<String, Integer> want = scale(a.mcqPlan() == null ? List.of() : a.mcqPlan(), count);
        Set<String> ids = want.keySet();
        Map<String, Integer> got = new LinkedHashMap<>();
        for (String s : subtopics) {
            String o = objectiveOf(s, ids);
            if (o != null) got.merge(o, 1, Integer::sum);
        }
        return want.entrySet().stream().map(e -> e.getKey() + " " + got.getOrDefault(e.getKey(), 0) + "/" + e.getValue()).collect(Collectors.joining(", "));
    }

    /** The video plan as a section of the presentation prompt. */
    public static String deckPlan(TopicAnalysis a, int wordsPerMinute) {
        if (a.videoPlan() == null || a.videoPlan().scenes() == null || a.videoPlan().scenes().isEmpty()) return "";
        Map<String, Objective> objectives = a.objectives() == null ? Map.of()
                : a.objectives().stream().collect(Collectors.toMap(Objective::id, o -> o, (x, y) -> x));
        Map<String, Concept> concepts = a.concepts() == null ? Map.of()
                : a.concepts().stream().collect(Collectors.toMap(Concept::id, c -> c, (x, y) -> x));
        int secs = a.videoPlan().scenes().stream().mapToInt(s -> s.seconds() == null ? 0 : s.seconds()).sum();
        StringBuilder sb = new StringBuilder("\nFollow this video plan from the approved topic analysis. The lesson video is made from this deck, ")
                .append("and the speaker notes are its narration: about ").append(wordsPerMinute).append(" words a minute, so a scene of N seconds ")
                .append("needs about N × ").append(wordsPerMinute).append(" ÷ 60 words of notes across its slides. Whole video: about ")
                .append(Math.round(secs / 60.0)).append(" minutes.\n");
        if (a.videoPlan().hook() != null) sb.append("Open with this hook: ").append(oneLine(a.videoPlan().hook())).append('\n');
        int i = 0;
        for (Scene s : a.videoPlan().scenes()) {
            i++;
            int sec = s.seconds() == null ? 60 : s.seconds();
            sb.append(i).append(". ").append(oneLine(s.title())).append(" — ").append(sec).append(" s, about ").append(Math.round(sec * wordsPerMinute / 60.0))
                    .append(" words");
            if (s.shows() != null) sb.append(", shows: ").append(s.shows().toLowerCase(java.util.Locale.ROOT));
            Objective o = s.goal() == null ? null : objectives.get(s.goal());
            if (o != null) sb.append(". Goal: ").append(oneLine(o.text()));
            List<String> terms = s.concepts() == null ? List.of() : s.concepts().stream().map(concepts::get).filter(c -> c != null).map(Concept::term).toList();
            if (!terms.isEmpty()) sb.append(". Concepts: ").append(String.join(", ", terms));
            sb.append('\n');
        }
        List<Misconception> mis = a.misconceptions() == null ? List.of() : a.misconceptions();
        if (!mis.isEmpty()) {
            sb.append("Address these misconceptions where they come up:\n");
            mis.forEach(m -> sb.append("- ").append(oneLine(m.wrong())).append(m.right() == null ? "" : " → " + oneLine(m.right())).append('\n'));
        }
        return sb.toString();
    }

    /** Slides for a plan: a title and a summary slide, plus one or two a scene by length. */
    public static int slidesFor(TopicAnalysis a, int fallback) {
        if (a.videoPlan() == null || a.videoPlan().scenes() == null || a.videoPlan().scenes().isEmpty()) return fallback;
        int n = 2;
        for (Scene s : a.videoPlan().scenes()) n += (s.seconds() != null && s.seconds() > 90) ? 2 : 1;
        return Math.max(6, Math.min(40, n));
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }
}
