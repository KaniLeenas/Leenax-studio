package com.leenax.studio.seo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.seo.Seo.Chapter;
import com.leenax.studio.seo.Seo.Metric;
import com.leenax.studio.seo.Seo.Report;
import com.leenax.studio.seo.Seo.Strategy;
import com.leenax.studio.seo.Seo.Suggestion;
import com.leenax.studio.seo.Seo.Title;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A topic's SEO report as an approval stage (E6). People edit the titles, the choice of
 * title, the description, tags, hashtags, thumbnail, chapters, playlists, next videos and
 * plans. The research it rests on can't be edited (see SeoStore). Numbers are rebuilt here so
 * they keep their labels whatever is sent.
 */
@Component
public class SeoReportRules implements Review.ContentHandler {

    static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Kind kind() {
        return Kind.SEO;
    }

    @Override
    public JsonNode normalise(JsonNode raw, UUID dependsOn) {
        Report r;
        try {
            r = JSON.treeToValue(raw, Report.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Not an SEO report");
        }
        if (r.topicId() == null || r.researchId() == null) throw ApiException.badRequest("An SEO report needs its topic and research");
        List<String> primary = clean(Seo.list(r.primaryKeywords()), 10, 150);
        List<Title> titles = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Title t : Seo.list(r.titles())) {
            if (titles.size() >= 15) throw ApiException.badRequest("At most 15 titles");
            String id = t.id() == null || t.id().isBlank() || !ids.add(t.id()) ? "t" + UUID.randomUUID().toString().substring(0, 8) : t.id();
            ids.add(id);
            String text = t.text() == null ? "" : t.text().strip().replaceAll("\\s+", " ");
            String style = Seo.TITLE_STYLES.contains(t.style()) ? t.style() : "EDUCATIONAL";
            String lang = "ta".equals(t.language()) ? "ta" : "en";
            titles.add(new Title(id, text, style, lang, SeoLogic.recheck(text, keepAi(t.check()), primary)));
        }
        String chosen = titles.stream().anyMatch(t -> t.id().equals(r.chosenTitleId())) ? r.chosenTitleId() : null;
        String recommended = titles.stream().anyMatch(t -> t.id().equals(r.recommendedTitleId())) ? r.recommendedTitleId() : null;
        List<Chapter> chapters = new ArrayList<>();
        for (Chapter c : Seo.list(r.chapters())) {
            if (c.title() == null || c.title().isBlank()) continue;
            double s = c.start() == null || c.start().value() == null ? 0 : Math.max(0, Math.round(c.start().value()));
            chapters.add(new Chapter(SeoAi.clip(c.title(), 100), Metric.estimate(s, "seconds",
                    "Planned start; set the real time after recording (YouTube needs the first chapter at 0:00)")));
            if (chapters.size() > 50) throw ApiException.badRequest("At most 50 chapters");
        }
        Map<String, Strategy> strategies = new LinkedHashMap<>();
        if (r.strategies() != null) {
            for (String k : Seo.STRATEGIES) {
                Strategy s = r.strategies().get(k);
                if (s != null) strategies.put(k, new Strategy(SeoAi.clip(s.titleIdea(), 200), SeoAi.clip(s.angle(), 1000), SeoAi.clip(s.format(), 300),
                        clean(Seo.list(s.keywords()), 10, 150), SeoAi.clip(s.notes(), 1500)));
            }
        }
        String description = r.description() == null ? null : r.description().strip();
        if (description != null && description.length() > Seo.DESCRIPTION_MAX) {
            throw ApiException.badRequest("YouTube descriptions are at most " + Seo.DESCRIPTION_MAX + " characters");
        }
        if (description != null && description.contains("<") && description.contains(">")) {
            throw ApiException.badRequest("YouTube doesn't allow < or > in descriptions");
        }
        Report out = new Report(r.topicId(), SeoAi.clip(r.topicTitle(), 300), r.researchId(), titles, recommended, SeoAi.clip(r.recommendationReason(), 1000),
                chosen, description, primary, clean(Seo.list(r.secondaryKeywords()), 20, 150), SeoAi.tags(Seo.list(r.tags())),
                SeoAi.hashtags(Seo.list(r.hashtags())), SeoAi.clip(r.thumbnailText(), 60), SeoAi.clip(r.thumbnailPrompt(), 1000), chapters,
                suggestions(r.playlists()), suggestions(r.nextVideos()), strategies, SeoAi.clip(r.model(), 120));
        return JSON.valueToTree(out);
    }

    /** The AI's scores survive only as Metrics labelled estimated; anything else is dropped. */
    static Seo.TitleCheck keepAi(Seo.TitleCheck c) {
        if (c == null) return null;
        Map<String, Metric> ai = null;
        if (c.ai() != null) {
            ai = new LinkedHashMap<>();
            for (var e : c.ai().entrySet()) {
                Metric m = e.getValue();
                if (Seo.TITLE_CRITERIA.contains(e.getKey()) && m != null && m.value() != null && m.value() >= 1 && m.value() <= 5
                        && Boolean.TRUE.equals(m.estimated())) ai.put(e.getKey(), Metric.estimate(m.value(), "1-5", m.method()));
            }
        }
        Metric overall = c.overall() != null && c.overall().value() != null && Boolean.TRUE.equals(c.overall().estimated())
                ? Metric.estimate(c.overall().value(), "1-5", c.overall().method()) : null;
        return new Seo.TitleCheck(c.textHash(), null, null, List.of(), ai == null || ai.isEmpty() ? null : ai, overall, SeoAi.clip(c.reasoning(), 500));
    }

    @Override
    public List<Check> checks(JsonNode content, Context ctx) {
        List<Check> out = new ArrayList<>();
        Report r;
        try {
            r = JSON.treeToValue(content, Report.class);
        } catch (Exception e) {
            return List.of(Check.fail("shape", "Not an SEO report"));
        }
        int n = Seo.list(r.titles()).size();
        out.add(n >= 10 && n <= 15 ? Check.pass("titles", n + " title options")
                : Check.fail("titles", n + " title options; the report needs 10 to 15"));
        Title chosen = Seo.list(r.titles()).stream().filter(t -> t.id().equals(r.chosenTitleId())).findFirst().orElse(null);
        if (chosen == null) out.add(Check.fail("chosen", "Choose the title to use"));
        else if (!SeoLogic.usable(chosen.check())) out.add(Check.fail("chosen", "The chosen title can't be used: " + String.join("; ", chosen.check().warnings())));
        else out.add(Check.pass("chosen", "Title chosen: “" + chosen.text() + "”"));
        long clickbait = Seo.list(r.titles()).stream().filter(t -> t.check() != null && t.check().warnings().contains("Sounds like clickbait")).count();
        if (clickbait > 0) out.add(Check.warn("clickbait", clickbait + " title option" + (clickbait == 1 ? " sounds" : "s sound") + " like clickbait"));
        out.add(r.description() == null || r.description().isBlank() ? Check.fail("description", "Write the description")
                : Check.pass("description", r.description().length() + " characters of description"));
        if (Seo.list(r.primaryKeywords()).isEmpty()) out.add(Check.warn("keywords", "No primary keywords chosen"));
        if (Seo.list(r.tags()).isEmpty()) out.add(Check.warn("tags", "No tags"));
        List<Chapter> ch = Seo.list(r.chapters());
        if (!ch.isEmpty() && ch.size() < 3) out.add(Check.warn("chapters", "YouTube shows chapters only when there are at least 3"));
        if (!ch.isEmpty() && ch.get(0).start().value() != null && ch.get(0).start().value() != 0) {
            out.add(Check.warn("chapters", "The first chapter must start at 0:00"));
        }
        List<String> missing = Seo.STRATEGIES.stream().filter(k -> r.strategies() == null || !r.strategies().containsKey(k)).toList();
        if (!missing.isEmpty()) out.add(Check.warn("strategies", "No plan yet for: " + String.join(", ", missing.stream().map(String::toLowerCase).toList())));
        List<String> bad = SeoLogic.unlabelledNumbers(content);
        out.add(bad.isEmpty() ? Check.pass("labels", "Every number has a source or an “estimated” label")
                : Check.fail("labels", "Numbers without a source or an “estimated” label: " + String.join(", ", bad.stream().limit(5).toList())));
        return out;
    }

    @Override
    public List<String> diff(JsonNode before, JsonNode after) {
        List<String> out = new ArrayList<>();
        String a = chosenText(before), b = chosenText(after);
        if (!Objects.equals(a, b)) out.add("Title: " + (a == null ? "none" : "“" + a + "”") + " → " + (b == null ? "none" : "“" + b + "”"));
        if (!before.path("description").equals(after.path("description"))) out.add("Description changed");
        if (before.path("titles").size() != after.path("titles").size()) out.add(before.path("titles").size() + " → " + after.path("titles").size() + " title options");
        if (!before.path("tags").equals(after.path("tags"))) out.add("Tags changed");
        if (!before.path("chapters").equals(after.path("chapters"))) out.add("Chapters changed");
        if (!before.path("strategies").equals(after.path("strategies"))) out.add("Plans changed");
        if (!before.path("researchId").equals(after.path("researchId"))) out.add("Built on new research");
        return out.isEmpty() ? List.of("Small changes") : out;
    }

    public static String chosenText(JsonNode r) {
        String id = r.path("chosenTitleId").asText(null);
        for (JsonNode t : r.path("titles")) if (t.path("id").asText().equals(id)) return t.path("text").asText();
        return null;
    }

    static List<String> clean(List<String> in, int max, int each) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            String x = SeoAi.clip(s == null ? null : s.replaceAll("\\s+", " "), each);
            if (x != null && !x.isBlank() && !out.contains(x)) out.add(x);
            if (out.size() >= max) break;
        }
        return out;
    }

    static List<Suggestion> suggestions(List<Suggestion> in) {
        List<Suggestion> out = new ArrayList<>();
        for (Suggestion s : Seo.list(in)) {
            if (s.title() != null && !s.title().isBlank()) out.add(new Suggestion(SeoAi.clip(s.title(), 200), SeoAi.clip(s.why(), 500)));
            if (out.size() >= 10) break;
        }
        return out;
    }
}
