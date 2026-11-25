package com.leenax.studio.qa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.qa.Qa.IssueRow;
import com.leenax.studio.qa.Qa.RunRow;
import com.leenax.studio.qa.Qa.Tally;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A topic's academic quality report (E5): notes, questions and the two combined. Built from
 * the QA runs and issues, never by an AI. Counts come first; the score is passed ÷ checked;
 * and the headline is Blocked whenever a critical issue is open, whatever the score.
 */
public final class QaReport {

    private QaReport() {
    }

    public static final String METHOD = """
            Each category counts what QA looked at: claims for accuracy; the topic list's subtopics for coverage; blocks of the \
            notes, and questions, for explanations; references; and questions for MCQ keys, difficulty and duplicates. \
            Failed is the number of issues in that category still open (not yet corrected and rechecked, or dismissed with a \
            reason). Passed is checked minus failed. The score is all passed ÷ all checked. The headline is Blocked while any \
            critical issue is open, whatever the score; Not checked while anything covered has no QA check of its current \
            version; Needs attention while a major issue is open; otherwise Passed. A Passed headline is not a guarantee that \
            everything is correct.""";

    public static final List<String> LIMITATIONS = List.of(
            "QA is done by AI models, which can miss errors and can report problems that aren't real. A person decides on every issue.",
            "Claims are checked against the course document pages found for the topic. Pages that weren't found can't be used.",
            "Outside links given by the AI are recorded as given; Leenax doesn't open or check them.",
            "Coverage of subtopics is estimated from the words used, so a subtopic covered under another name can be flagged.",
            "Repeated questions are found by shared wording, not meaning.");

    // ---- content -----------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunInfo(UUID runId, int versionNo, String model, String provider, String secondModel, String writerModel,
                          boolean independent, String at, boolean current) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(UUID resourceId, String kind, String level, String label, UUID versionId, int versionNo, String versionStatus,
                       RunInfo run, Map<String, Tally> categories, Map<String, Integer> claimsByBasis, Map<String, Integer> difficulty,
                       Integer questions, Map<String, Integer> openBySeverity, int corrected, int dismissed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IssueLine(int number, UUID resourceId, String resource, String where, String severity, String type, String category,
                            String status, String explanation, String basis, String correction, String statusNote) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CorrectionLine(int issue, String resource, String status, String decidedBy, String at, String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Coverage(String subtopic, boolean covered, List<String> levels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(int checked, int passed, int failed, int corrected, int open, int openCritical, Integer score, String headline,
                       Map<String, Tally> categories) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Report(String topicId, String topicTitle, String generatedAt, List<Item> items, List<IssueLine> issues,
                         List<CorrectionLine> corrections, List<Coverage> coverage, Map<String, Part> parts, String headline,
                         String method, List<String> recommendations, List<String> limitations) {
    }

    // ---- building ----------------------------------------------------------------------------------

    /** One covered resource as found in the database. */
    public record Source(UUID resourceId, String kind, String level, String label, UUID versionId, int versionNo, String versionStatus,
                         String contentHash, String text, List<RunRow> runs, Map<UUID, String> runHashes, List<IssueRow> issues,
                         List<Qa.CorrectionRow> corrections, Map<String, Integer> difficulty, Integer questions) {
    }

    public static Report build(String topicId, String topicTitle, String now, List<Source> sources, List<String> subtopics) {
        List<Item> items = new ArrayList<>();
        List<IssueLine> issues = new ArrayList<>();
        List<CorrectionLine> corrections = new ArrayList<>();
        for (Source s : sources) {
            RunRow run = s.runs().stream().filter(r -> r.mode().equals("CHECK")).findFirst().orElse(null);
            boolean current = run != null && s.contentHash().equals(s.runHashes().get(run.id()));
            Map<String, Tally> cats = tallies(run, s.issues());
            Map<String, Integer> bases = new LinkedHashMap<>();
            if (run != null) {
                for (JsonNode c : run.result().path("claims")) bases.merge(c.path("basis").asText("UNVERIFIED"), 1, Integer::sum);
            }
            Map<String, Integer> open = new LinkedHashMap<>();
            int corrected = 0, dismissed = 0;
            for (IssueRow i : s.issues()) {
                if (Qa.open(i.status())) open.merge(i.severity(), 1, Integer::sum);
                if (i.status().equals("RESOLVED") && i.correction() != null && "ACCEPTED".equals(i.correction().status())) corrected++;
                if (i.status().equals("DISMISSED")) dismissed++;
                issues.add(new IssueLine(i.number(), s.resourceId(), s.label(), i.itemLabel(), i.severity(), i.type(), i.category(), i.status(),
                        clip(i.explanation(), 400), i.basis(), i.correction() == null ? null : i.correction().status(), i.statusNote()));
            }
            for (Qa.CorrectionRow c : s.corrections()) {
                int number = s.issues().stream().filter(i -> i.id().equals(c.issueId())).map(IssueRow::number).findFirst().orElse(0);
                corrections.add(new CorrectionLine(number, s.label(), c.status(), c.decidedBy(), c.decidedAt() == null ? null : c.decidedAt().toString(),
                        c.note()));
            }
            RunInfo info = run == null ? null : new RunInfo(run.id(), run.versionNo(), run.model(), run.provider(), run.secondModel(),
                    run.writerModel(), run.independent(), run.createdAt() == null ? null : run.createdAt().toString(), current);
            items.add(new Item(s.resourceId(), s.kind(), s.level(), s.label(), s.versionId(), s.versionNo(), s.versionStatus(), info, cats,
                    bases, s.difficulty(), s.questions(), open, corrected, dismissed));
        }
        issues.sort((a, b) -> {
            int c = Integer.compare(rank(a.severity()), rank(b.severity()));
            if (c != 0) return c;
            c = Boolean.compare(!Qa.open(a.status()), !Qa.open(b.status()));
            if (c != 0) return c;
            c = a.resource().compareTo(b.resource());
            return c != 0 ? c : Integer.compare(a.number(), b.number());
        });

        List<Coverage> coverage = new ArrayList<>();
        for (String sub : subtopics) {
            List<String> where = sources.stream().filter(s -> s.kind().equals("NOTES") && s.text() != null && QaRules.covered(sub, s.text()))
                    .map(Source::label).toList();
            coverage.add(new Coverage(sub, !where.isEmpty(), where));
        }

        Map<String, Part> parts = new LinkedHashMap<>();
        parts.put("NOTES", part(items, issues, "NOTES"));
        parts.put("MCQ", part(items, issues, "MCQ"));
        parts.put("COMBINED", part(items, issues, null));
        String headline = parts.get("COMBINED").headline();
        return new Report(topicId, topicTitle, now, items, issues, corrections, coverage, parts, headline, METHOD,
                recommendations(items, coverage, parts.get("COMBINED")), limitations(items));
    }

    /** Checked per category from the run; failed = issues in that category still open. */
    static Map<String, Tally> tallies(RunRow run, List<IssueRow> issues) {
        Map<String, Tally> out = new LinkedHashMap<>();
        if (run == null) return out;
        JsonNode cats = run.result().path("categories");
        for (String c : Qa.CATEGORIES) {
            int checked = cats.path(c).path("checked").asInt(0);
            if (checked == 0) continue;
            int failed = (int) issues.stream().filter(i -> c.equals(i.category()) && Qa.open(i.status())).count();
            out.put(c, Tally.of(checked, Math.min(failed, checked)));
        }
        return out;
    }

    static Part part(List<Item> items, List<IssueLine> issues, String kind) {
        List<Item> mine = items.stream().filter(i -> kind == null || i.kind().equals(kind)).toList();
        Map<String, Tally> cats = new LinkedHashMap<>();
        int corrected = 0;
        for (Item i : mine) {
            i.categories().forEach((k, v) -> cats.merge(k, v, Tally::plus));
            corrected += i.corrected();
        }
        int checked = cats.values().stream().mapToInt(Tally::checked).sum();
        int failed = cats.values().stream().mapToInt(Tally::failed).sum();
        List<IssueLine> mineIssues = issues.stream().filter(x -> mine.stream().anyMatch(i -> i.resourceId().equals(x.resourceId()))).toList();
        int open = (int) mineIssues.stream().filter(x -> Qa.open(x.status())).count();
        int critical = (int) mineIssues.stream().filter(x -> Qa.open(x.status()) && x.severity().equals("CRITICAL")).count();
        int major = (int) mineIssues.stream().filter(x -> Qa.open(x.status()) && x.severity().equals("MAJOR")).count();
        Integer score = checked == 0 ? null : (int) Math.round(100.0 * (checked - failed) / checked);
        String headline;
        if (critical > 0) headline = "BLOCKED";
        else if (mine.isEmpty() || mine.stream().anyMatch(i -> i.run() == null || !i.run().current())) headline = "NOT_CHECKED";
        else if (major > 0) headline = "NEEDS_ATTENTION";
        else headline = "PASSED";
        return new Part(checked, checked - failed, failed, corrected, open, critical, score, headline, cats);
    }

    static List<String> recommendations(List<Item> items, List<Coverage> coverage, Part all) {
        List<String> out = new ArrayList<>();
        if (all.openCritical() > 0) {
            out.add("Deal with the " + all.openCritical() + " open critical issue" + (all.openCritical() == 1 ? "" : "s")
                    + ": accept or make a correction and recheck, or have a reviewer dismiss it with a reason. Nothing here can be approved until then.");
        }
        for (Item i : items) {
            if (i.run() == null) out.add("Run QA on " + i.label() + " (version " + i.versionNo() + "); it hasn't been checked.");
            else if (!i.run().current()) out.add("Run QA again on " + i.label() + ": it changed after version " + i.run().versionNo() + " was checked.");
            else if (!i.run().independent()) out.add("Check " + i.label() + " with a different model: the one that wrote it also checked it.");
        }
        long gaps = coverage.stream().filter(c -> !c.covered()).count();
        if (gaps > 0) out.add(gaps + " subtopic" + (gaps == 1 ? " doesn't" : "s don't") + " appear in any level of the notes: "
                + String.join(", ", coverage.stream().filter(c -> !c.covered()).map(Coverage::subtopic).toList()) + ".");
        int major = all.open() - all.openCritical();
        if (major > 0) out.add("Review the " + major + " other open issue" + (major == 1 ? "" : "s") + ".");
        if (items.stream().noneMatch(i -> i.kind().equals("MCQ"))) out.add("This topic has no question bank yet.");
        if (out.isEmpty()) out.add("No action needed from QA. A person still reads and approves the content.");
        return out;
    }

    static List<String> limitations(List<Item> items) {
        List<String> out = new ArrayList<>(LIMITATIONS);
        for (Item i : items) {
            if (i.run() != null && !i.run().independent()) {
                out.add(i.label() + " was checked by " + i.run().model() + ", the model that wrote it, so that check isn't independent.");
            }
            if (i.run() != null && i.run().secondModel() == null) {
                out.add(i.label() + ": no second model confirmed the findings.");
            }
        }
        return out;
    }

    static int rank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 0;
            case "MAJOR" -> 1;
            default -> 2;
        };
    }

    static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
