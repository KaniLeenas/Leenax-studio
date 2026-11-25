package com.leenax.studio.seo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.text.TextAi;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.Review.Origin;
import com.leenax.studio.approvals.Review.Status;
import com.leenax.studio.approvals.ReviewDtos.ResourceState;
import com.leenax.studio.approvals.ReviewService;
import com.leenax.studio.approvals.ReviewService.Target;
import com.leenax.studio.approvals.ReviewStore;
import com.leenax.studio.approvals.ReviewStore.ResourceRow;
import com.leenax.studio.approvals.ReviewStore.VersionRow;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.common.SqlStates;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.notes.MaterialsAi.TopicBrief;
import com.leenax.studio.notes.MaterialsService;
import com.leenax.studio.notes.McqRules;
import com.leenax.studio.notes.NoteDoc;
import com.leenax.studio.notes.NotesRules;
import com.leenax.studio.projects.Project;
import com.leenax.studio.projects.ProjectService;
import com.leenax.studio.qa.QaRules;
import com.leenax.studio.seo.Seo.Competitor;
import com.leenax.studio.seo.Seo.Coverage;
import com.leenax.studio.seo.Seo.Idea;
import com.leenax.studio.seo.Seo.Keyword;
import com.leenax.studio.seo.Seo.Metric;
import com.leenax.studio.seo.Seo.Report;
import com.leenax.studio.seo.Seo.Research;
import com.leenax.studio.seo.Seo.Search;
import com.leenax.studio.seo.Seo.Source;
import com.leenax.studio.seo.Seo.Suggestion;
import com.leenax.studio.seo.Seo.TrendUse;
import com.leenax.studio.seo.SeoLogic.Weights;
import com.leenax.studio.seo.SeoStore.ResearchRow;
import com.leenax.studio.seo.SeoStore.TrendsRow;
import com.leenax.studio.social.SocialAccount;
import com.leenax.studio.social.SocialAccountService;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialPlatform;
import com.leenax.studio.social.YouTubeClient;
import com.leenax.studio.social.YouTubeClient.VideoInfo;
import com.leenax.studio.syllabus.AnalysisStore;
import com.leenax.studio.syllabus.AnalysisStore.JobRow;
import com.leenax.studio.syllabus.AnalysisWorker;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * YouTube SEO research and reports (E6). Searches go through YouTube's Data API within a daily
 * budget (shared by the install, counted by Pacific day) and a 7-day cache. Keyword ideas and
 * the titles and plans come from your own AI account; demand comes only from Trends files you
 * import. Every number is labelled; the report is a draft that people edit and approve.
 */
@Service
public class SeoService implements AnalysisWorker.Runner {

    private static final Logger log = LoggerFactory.getLogger(SeoService.class);
    static final ObjectMapper JSON = new ObjectMapper();
    static final int RESULTS_PER_SEARCH = 20;
    static final int MAX_SEARCHES_PER_RUN = 10;
    static final long MAX_TRENDS_BYTES = 1_000_000;

    public record ResearchRequest(@NotBlank @Size(max = 40) String topicId, UUID youtubeAccountId, @NotNull UUID accountId,
                                  @NotBlank String model, List<String> languages, @Size(max = 2) String region, Integer searches,
                                  List<UUID> trendsIds, @Size(max = 1000) String guidance) {
    }

    public record Budget(int dailySearches, long usedToday, long left, long unitsToday, int cacheDays, Instant resetsAt, String quotaDay) {
    }

    public record TrendsInfo(UUID id, String filename, String geo, String period, LocalDate firstDate, LocalDate lastDate, List<String> terms,
                             String status, String createdBy, Instant createdAt) {
    }

    public record TopicSeo(String topicId, String title, int depth, boolean hasNotes, UUID researchId, String researchStatus,
                           Instant researchedAt, UUID reportId, Integer reportVersionNo, String reportStatus, String chosenTitle,
                           boolean running, String jobError) {
    }

    public record YouTubeAccount(UUID id, String name, String status) {
    }

    public record Overview(boolean topicsApproved, Budget budget, List<TrendsInfo> trends, List<TopicSeo> topics, List<YouTubeAccount> youtube,
                           boolean canEdit, Weights weights) {
    }

    private final ReviewService reviews;
    private final ReviewStore reviewStore;
    private final SeoStore store;
    private final AnalysisStore jobs;
    private final MaterialsService materials;
    private final ProjectService projects;
    private final TextAi textAi;
    private final YouTubeClient youtube;
    private final SocialAccountService social;
    private final AuditService audit;
    private final int dailySearches;
    private final int cacheDays;
    private final Weights weights;

    public SeoService(ReviewService reviews, ReviewStore reviewStore, SeoStore store, AnalysisStore jobs, MaterialsService materials,
                      ProjectService projects, TextAi textAi, YouTubeClient youtube, SocialAccountService social, AuditService audit,
                      @Value("${leenax.seo.daily-searches:100}") int dailySearches, @Value("${leenax.seo.cache-days:7}") int cacheDays,
                      @Value("${leenax.seo.weight-demand:0.4}") double wD, @Value("${leenax.seo.weight-competition:0.3}") double wC,
                      @Value("${leenax.seo.weight-gap:0.3}") double wG) {
        this.reviews = reviews;
        this.reviewStore = reviewStore;
        this.store = store;
        this.jobs = jobs;
        this.materials = materials;
        this.projects = projects;
        this.textAi = textAi;
        this.youtube = youtube;
        this.social = social;
        this.audit = audit;
        this.dailySearches = Math.max(0, dailySearches);
        this.cacheDays = Math.max(0, cacheDays);
        Weights d = Weights.defaults();
        this.weights = new Weights(wD, wC, wG, d.recentDays(), d.longSeconds(), d.manyViews());
    }

    // ---- reading -----------------------------------------------------------------------------------

    public Budget budget() {
        Instant now = Instant.now();
        LocalDate day = SeoStore.quotaDay(now);
        long[] used = store.used(day);
        Instant reset = day.plusDays(1).atStartOfDay(SeoStore.QUOTA_ZONE).toInstant();
        return new Budget(dailySearches, used[0], Math.max(0, dailySearches - used[0]), used[1], cacheDays, reset, day.toString());
    }

    public Overview overview(UUID projectId) {
        projects.requireAccess(projectId, false, null);
        Optional<VersionRow> topics = reviews.approved(projectId, Kind.TOPICS);
        Map<String, ResearchRow> research = new HashMap<>();
        store.latestPerTopic(projectId).forEach(r -> research.put(r.topicId(), r));
        Map<String, ResourceRow> reports = new HashMap<>();
        reviewStore.resources(projectId, Kind.SEO).forEach(r -> reports.put(r.topicId(), r));
        Set<String> withNotes = new java.util.HashSet<>();
        reviewStore.resources(projectId, Kind.NOTES).forEach(r -> withNotes.add(r.topicId()));
        Map<String, JobRow> latestJob = new HashMap<>();
        for (JobRow j : jobs.recent(projectId, "SEO")) latestJob.putIfAbsent(j.target(), j);
        List<TopicSeo> out = new ArrayList<>();
        for (MaterialsService.TopicRow t : topics.map(v -> MaterialsService.topicRows(MaterialsService.read(v.content()))).orElse(List.of())) {
            ResearchRow r = research.get(t.id());
            ResourceRow rep = reports.get(t.id());
            VersionRow rv = rep == null ? null : newest(rep);
            String chosen = rv == null ? null : SeoReportRules.chosenText(MaterialsService.read(reviewStore.version(rv.id()).orElseThrow().content()));
            JobRow j = latestJob.get(t.id());
            boolean running = j != null && (j.status().equals("QUEUED") || j.status().equals("RUNNING"));
            out.add(new TopicSeo(t.id(), t.title(), t.depth(), withNotes.contains(t.id()), r == null ? null : r.id(), r == null ? null : r.status(),
                    r == null ? null : r.createdAt(), rep == null ? null : rep.id(), rv == null ? null : rv.versionNo(),
                    rv == null ? null : rv.status().name(), chosen, running, j != null && j.status().equals("FAILED") ? j.error() : null));
        }
        List<YouTubeAccount> yt = social.list().stream().filter(a -> "YOUTUBE".equals(String.valueOf(a.platform())))
                .map(a -> new YouTubeAccount(a.id(), a.displayName(), String.valueOf(a.status()))).toList();
        return new Overview(topics.isPresent(), budget(), store.trends(projectId, true).stream().map(SeoService::info).toList(), out, yt,
                projects.canEdit(projectId), weights);
    }

    public ResearchRow research(UUID researchId) {
        ResearchRow r = store.research(researchId).orElseThrow(() -> ApiException.notFound("Research"));
        projects.requireAccess(r.projectId(), false, null);
        return r;
    }

    // ---- Trends imports ----------------------------------------------------------------------------

    @Transactional
    public TrendsInfo importTrends(UUID projectId, String filename, byte[] bytes) {
        projects.requireAccess(projectId, true, "Only the project's owners and editors import Trends files");
        if (bytes == null || bytes.length == 0) throw ApiException.badRequest("The file is empty");
        if (bytes.length > MAX_TRENDS_BYTES) throw ApiException.badRequest("A Trends CSV is much smaller than 1 MB; this doesn't look like one");
        String name = filename == null || filename.isBlank() ? "trends.csv" : filename.strip();
        if (name.length() > 200) name = name.substring(0, 200);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) throw ApiException.badRequest("Upload the CSV file downloaded from Google Trends");
        SeoLogic.Trends t = SeoLogic.parseTrends(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        UUID id = UUID.randomUUID();
        store.insertTrends(id, projectId, name, t);
        audit.record("SEO_TRENDS_IMPORTED", "project", projectId, Map.of("terms", t.terms().size()));
        return info(store.trendsFile(id).orElseThrow());
    }

    @Transactional
    public void removeTrends(UUID trendsId) {
        UUID projectId = store.projectOfTrends(trendsId);
        if (projectId == null) throw ApiException.notFound("Trends file");
        projects.requireAccess(projectId, true, "Only the project's owners and editors remove Trends files");
        if (store.removeTrends(trendsId) == 0) throw new ApiException(ErrorCode.CONFLICT, "This file was already removed");
    }

    // ---- starting research -------------------------------------------------------------------------

    public JobRow start(UUID projectId, ResearchRequest req) {
        projects.requireAccess(projectId, true, "Only the project's owners and editors run SEO research");
        VersionRow topics = reviews.approved(projectId, Kind.TOPICS)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Approve the topic list first."));
        materials.brief(projectId, MaterialsService.read(topics.content()), req.topicId()); // the topic must be in it
        reviewStore.resource(projectId, Kind.SEO, req.topicId(), null).map(this::newest).filter(v -> v.status() == Status.IN_REVIEW)
                .ifPresent(v -> {
                    throw new ApiException(ErrorCode.CONFLICT, "The topic's SEO report is in review. Withdraw it first, or wait for the decision.");
                });
        List<String> languages = req.languages() == null || req.languages().isEmpty() ? List.of("en", "ta")
                : req.languages().stream().filter(l -> l.equals("en") || l.equals("ta")).distinct().toList();
        if (languages.isEmpty()) throw ApiException.badRequest("Choose English, Tamil or both");
        int searches = req.searches() == null ? 5 : req.searches();
        if (searches < 0 || searches > MAX_SEARCHES_PER_RUN) throw ApiException.badRequest("Use 0 to " + MAX_SEARCHES_PER_RUN + " searches a run");
        if (searches > 0) {
            if (req.youtubeAccountId() == null) throw ApiException.badRequest("Choose the YouTube account to search with, or set searches to 0");
            SocialAccount a = social.find(req.youtubeAccountId());
            if (a.getPlatform() != SocialPlatform.YOUTUBE) throw ApiException.badRequest("That isn't a YouTube account");
            long left = budget().left();
            if (left < searches) {
                throw new ApiException(ErrorCode.CONFLICT, left == 0 ? "No YouTube searches are left today. Run with 0 searches (cached results only), or wait for the reset."
                        : "Only " + left + " YouTube search" + (left == 1 ? " is" : "es are") + " left today. Ask for " + left + " or fewer.");
            }
        }
        String region = req.region() == null || req.region().isBlank() ? "LK" : req.region().strip().toUpperCase(Locale.ROOT);
        if (!region.matches("[A-Z]{2}")) throw ApiException.badRequest("The region is a two-letter country code, like LK");
        List<UUID> trends = req.trendsIds() == null ? List.of() : req.trendsIds();
        for (UUID t : trends) {
            if (!projectId.equals(store.projectOfTrends(t))) throw ApiException.badRequest("That Trends file isn't in this project");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("topicId", req.topicId());
        if (req.youtubeAccountId() != null) input.put("youtubeAccountId", req.youtubeAccountId());
        input.put("accountId", req.accountId());
        input.put("model", req.model().strip());
        input.put("languages", languages);
        input.put("region", region);
        input.put("searches", searches);
        input.put("trendsIds", trends);
        if (req.guidance() != null && !req.guidance().isBlank()) input.put("guidance", req.guidance().strip());
        UUID id = UUID.randomUUID();
        try {
            jobs.insert(id, projectId, "SEO", req.topicId(), write(input));
        } catch (RuntimeException e) {
            if (SqlStates.find(e).map(x -> "23505".equals(x.getSQLState())).orElse(false)) {
                throw new ApiException(ErrorCode.CONFLICT, "SEO research is already running for this topic.");
            }
            throw e;
        }
        audit.record("SEO_RESEARCH_STARTED", "project", projectId, Map.of("topic", req.topicId(), "searches", searches));
        return jobs.job(id).orElseThrow();
    }

    // ---- the job -----------------------------------------------------------------------------------

    @Override
    public boolean handles(String kind) {
        return kind.equals("SEO");
    }

    @Override
    public void process(UUID jobId) {
        JobRow j = jobs.job(jobId).orElse(null);
        if (j == null || !(j.status().equals("QUEUED") || j.status().equals("RUNNING"))) return;
        jobs.running(jobId);
        try {
            String summary = run(j, JSON.readTree(j.input()));
            jobs.summary(jobId, summary);
            jobs.succeeded(jobId, null);
        } catch (ProviderException e) {
            if (e.kind().retryable() && j.attempts() + 1 < 2) jobs.retryLater(jobId, e.getMessage() + " — trying again shortly", 120);
            else jobs.failed(jobId, e.getMessage());
        } catch (ApiException e) {
            jobs.failed(jobId, e.getMessage());
        } catch (Exception e) {
            log.error("SEO job {} failed", jobId, e);
            jobs.failed(jobId, "SEO research failed unexpectedly");
        }
    }

    String run(JobRow j, JsonNode in) {
        UUID projectId = j.projectId();
        String topicId = in.path("topicId").asText();
        UUID account = UUID.fromString(in.path("accountId").asText());
        String model = in.path("model").asText();
        List<String> languages = new ArrayList<>();
        in.path("languages").forEach(l -> languages.add(l.asText()));
        String region = in.path("region").asText("LK");
        int searchesWanted = in.path("searches").asInt(0);
        UUID ytAccount = in.hasNonNull("youtubeAccountId") ? UUID.fromString(in.path("youtubeAccountId").asText()) : null;
        String guidance = in.path("guidance").asText(null);
        Instant started = Instant.now();
        List<String> limitations = new ArrayList<>();
        List<Source> sources = new ArrayList<>();

        // the topic, its notes and questions
        VersionRow topicsVersion = reviews.approved(projectId, Kind.TOPICS)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "The topic list is no longer approved."));
        JsonNode topicsContent = MaterialsService.read(topicsVersion.content());
        TopicBrief brief = materials.brief(projectId, topicsContent, topicId);
        sources.add(new Source("Approved topic list", "Version " + topicsVersion.versionNo(), null));
        List<String> headings = new ArrayList<>();
        StringBuilder notesText = new StringBuilder();
        for (ResourceRow r : reviewStore.resources(projectId, Kind.NOTES)) {
            if (!topicId.equals(r.topicId())) continue;
            VersionRow v = reviewStore.approved(r.id()).orElse(null);
            if (v == null) continue;
            var n = NotesRules.read(MaterialsService.read(reviewStore.version(v.id()).orElseThrow().content()));
            for (String h : NoteDoc.headings(n.doc())) if (!headings.contains(h)) headings.add(h);
            if (notesText.length() < 60_000) notesText.append(NoteDoc.plainText(n.doc())).append('\n');
            sources.add(new Source("Approved notes", Learning.levelLabel(r.level()) + ", version " + v.versionNo(), null));
        }
        if (headings.isEmpty()) limitations.add("No approved notes for this topic yet, so relevance and chapters rest on the topic list only.");
        int questions = 0;
        for (ResourceRow r : reviewStore.resources(projectId, Kind.MCQ)) {
            if (!topicId.equals(r.topicId())) continue;
            Optional<VersionRow> v = reviewStore.approved(r.id());
            if (v.isPresent()) questions = McqRules.list(McqRules.read(MaterialsService.read(reviewStore.version(v.get().id()).orElseThrow().content())).questions()).size();
        }
        String topicText = brief.title() + "\n" + String.join("\n", brief.subtopics()) + "\n" + String.join("\n", headings) + "\n" + notesText;
        Project project = projects.requireAccess(projectId, false, null);

        // 1. keyword ideas from the AI
        List<Idea> ideas = SeoAi.readIdeas(textAi.askJson(account, model, SeoAi.ideasSystem(),
                SeoAi.ideasPrompt(brief.title(), brief.path(), brief.subtopics(), headings, languages, project.getSubjectName(), guidance)), languages, model);
        if (ideas.isEmpty()) throw new ApiException(ErrorCode.CONFLICT, "The AI suggested no usable keywords. Try again or with another model.");
        sources.add(new Source("Keyword ideas", "AI suggestion by " + model + " (ideas only, no figures)", started.toString()));

        // 2. YouTube searches within the budget, cache first
        List<String> queries = pickQueries(ideas, languages, searchesWanted);
        Map<String, List<VideoInfo>> resultsByQuery = new LinkedHashMap<>();
        List<Search> searches = new ArrayList<>();
        Map<String, String> retrievedAt = new HashMap<>();
        long usedBefore = store.used(SeoStore.quotaDay(started))[0];
        int spent = 0;
        String token = null;
        boolean stopped = false;
        for (String q : queries) {
            String lang = SeoAi.guessLanguage(q);
            String key = SeoStore.cacheKey(q, lang, region);
            Optional<SeoStore.Cached> hit = cacheDays == 0 ? Optional.empty() : store.cached(projectId, key, started.minusSeconds(86_400L * cacheDays));
            if (hit.isPresent()) {
                List<VideoInfo> vids = videosOf(hit.get().results());
                resultsByQuery.put(q, vids);
                retrievedAt.put(q, hit.get().retrievedAt().toString());
                searches.add(new Search(q, lang, region, hit.get().retrievedAt().toString(), true, vids.stream().map(VideoInfo::id).toList(),
                        Metric.of(vids.size(), "videos", Seo.YT_SEARCH + " (cached)", hit.get().retrievedAt().toString())));
                continue;
            }
            if (stopped || ytAccount == null) continue;
            LocalDate day = SeoStore.quotaDay(Instant.now());
            if (store.used(day)[0] >= dailySearches) {
                limitations.add("The daily YouTube search budget ran out during this run; " + (queries.size() - searches.size()) + " keyword(s) weren't searched.");
                stopped = true;
                continue;
            }
            try {
                if (token == null) token = social.accessToken(ytAccount);
                List<String> ids;
                try {
                    ids = youtube.search(token, q, lang, region, RESULTS_PER_SEARCH);
                    store.recordCall(projectId, day, "search.list", 100, q, true);
                    spent++;
                } catch (SocialException e) {
                    store.recordCall(projectId, day, "search.list", 100, q, false);
                    throw e;
                }
                List<VideoInfo> vids = new ArrayList<>();
                if (!ids.isEmpty()) {
                    try {
                        List<VideoInfo> got = youtube.videos(token, ids);
                        store.recordCall(projectId, day, "videos.list", 1, null, true);
                        Map<String, VideoInfo> byId = new HashMap<>();
                        got.forEach(v -> byId.put(v.id(), v));
                        for (String id : ids) if (byId.containsKey(id)) vids.add(byId.get(id));
                    } catch (SocialException e) {
                        store.recordCall(projectId, day, "videos.list", 1, null, false);
                        throw e;
                    }
                }
                Instant at = Instant.now();
                store.cache(projectId, key, q, lang, region, Map.of("videos", vids), at);
                resultsByQuery.put(q, vids);
                retrievedAt.put(q, at.toString());
                searches.add(new Search(q, lang, region, at.toString(), false, vids.stream().map(VideoInfo::id).toList(),
                        Metric.of(vids.size(), "videos", Seo.YT_SEARCH, at.toString())));
            } catch (SocialException e) {
                stopped = true;
                limitations.add(switch (e.kind()) {
                    case RATE_LIMITED -> "YouTube's daily quota ran out during this run (" + e.getMessage() + "); the rest weren't searched.";
                    case RECONNECT -> "The YouTube account needs reconnecting (" + e.getMessage() + "); nothing more was searched.";
                    case NOT_CONFIGURED -> "YouTube isn't set up on this install; nothing was searched.";
                    default -> "YouTube didn't answer (" + e.getMessage() + "); the rest weren't searched.";
                });
            }
        }
        if (searchesWanted == 0) limitations.add("No YouTube searches were asked for; only cached results (if any) were used.");
        else if (ytAccount == null) limitations.add("No YouTube account was chosen, so nothing was searched.");
        if (!searches.isEmpty()) sources.add(new Source(Seo.YT_SEARCH, searches.size() + " keyword(s), region " + region + ", top "
                + RESULTS_PER_SEARCH + " results each" + (spent < searches.size() ? " (" + (searches.size() - spent) + " from the 7-day cache)" : ""), null));

        // 3. Trends files
        List<TrendsRow> trendFiles = new ArrayList<>();
        for (JsonNode t : in.path("trendsIds")) store.trendsFile(UUID.fromString(t.asText())).ifPresent(trendFiles::add);
        for (TrendsRow t : trendFiles) {
            sources.add(new Source("Google Trends CSV", "“" + t.filename() + "”" + (t.geo() == null ? "" : ", " + t.geo()) + ", " + t.firstDate() + " to "
                    + t.lastDate() + "; relative 0–100 within that download", t.createdAt().toString()));
        }
        if (trendFiles.isEmpty()) limitations.add("No Google Trends file was used, so every opportunity score is partial (no demand signal).");

        // 4. score every keyword
        List<Keyword> keywords = new ArrayList<>();
        List<TrendUse> trendUses = new ArrayList<>();
        for (Idea i : ideas) {
            Metric r = SeoLogic.relevance(i.text(), topicText);
            List<VideoInfo> res = resultsByQuery.get(i.text());
            Metric d = null;
            String trend = null;
            for (TrendsRow t : trendFiles) {
                List<String> terms = new ArrayList<>();
                t.terms().forEach(x -> terms.add(x.asText()));
                String term = SeoLogic.matchTerm(i.text(), terms);
                if (term == null) continue;
                List<Double> series = new ArrayList<>();
                t.series().path(term).forEach(x -> series.add(x.asDouble()));
                d = SeoLogic.demand(series, t.filename(), t.createdAt().toString(), t.geo());
                trend = direction(series);
                trendUses.add(new TrendUse(t.id(), t.filename(), term, t.geo(), t.period(), String.valueOf(t.lastDate()), d, trend));
                break;
            }
            Metric c = null, g = null, s = null;
            if (res != null) {
                c = SeoLogic.competition(res, started, weights, retrievedAt.get(i.text()));
                g = SeoLogic.gap(i.text(), res);
                s = SeoLogic.score(r, d, c, g, weights);
            }
            keywords.add(new Keyword(i.text(), i.language(), i.kind(), i.intent(), i.audience(), i.source(), res != null, r, d, c, g, s,
                    s == null ? null : d == null, trend));
        }
        keywords.sort(Comparator.comparing((Keyword k) -> k.score() == null ? -1 : k.score().value()).reversed());

        // 5. competitors and coverage, from public data only
        Map<String, Competitor> comp = new LinkedHashMap<>();
        Map<String, List<String>> foundBy = new HashMap<>();
        Map<String, Integer> bestRank = new HashMap<>();
        for (var e : resultsByQuery.entrySet()) {
            int rank = 0;
            for (VideoInfo v : e.getValue()) {
                rank++;
                foundBy.computeIfAbsent(v.id(), k -> new ArrayList<>()).add(e.getKey());
                bestRank.merge(v.id(), rank, Math::min);
                if (!comp.containsKey(v.id())) comp.put(v.id(), competitor(v, retrievedAt.get(e.getKey()), brief.subtopics()));
            }
        }
        List<Competitor> competitors = comp.values().stream()
                .map(c -> new Competitor(c.videoId(), c.url(), c.title(), c.channel(), c.publishedAt(), c.duration(), c.views(), c.likes(), c.comments(),
                        c.description(), c.tags(), c.thumbnail(), c.covers(), foundBy.get(c.videoId()), bestRank.get(c.videoId())))
                .sorted(Comparator.comparing(Competitor::rank)).limit(30).toList();
        if (!competitors.isEmpty()) sources.add(new Source(Seo.YT_VIDEOS, competitors.size() + " videos: views, likes and comments where public, length, "
                + "description, tags where returned", null));
        List<Coverage> coverage = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        String anyAt = retrievedAt.values().stream().findFirst().orElse(null);
        for (String sub : brief.subtopics()) {
            long n = competitors.stream().filter(c -> c.covers().contains(sub)).count();
            coverage.add(new Coverage(sub, Metric.of(n, "of " + competitors.size() + " competitor videos", Seo.COUNTED + " from titles and descriptions ("
                    + Seo.YT_VIDEOS + ")", anyAt)));
            if (!competitors.isEmpty() && n * 4 <= competitors.size()) gaps.add(sub);
        }

        // 6. the plan: analysis, titles, description, tags, chapters, strategies
        List<String> primaryFirst = keywords.stream().filter(k -> k.kind().equals("PRIMARY")).map(Keyword::text).limit(6).toList();
        final List<String> primary = primaryFirst.isEmpty() ? keywords.stream().map(Keyword::text).limit(4).toList() : primaryFirst;
        SeoAi.Plan plan = SeoAi.readPlan(textAi.askJson(account, model, SeoAi.planSystem(), SeoAi.planPrompt(brief.title(), brief.subtopics(), headings,
                questions, keywords, competitors, gaps, languages, guidance)), primary, languages, model);
        if (plan.titles().size() < 10) limitations.add("The AI gave " + plan.titles().size() + " usable titles; add more by hand to reach 10.");
        sources.add(new Source("Titles, description and plans", "Written by " + model + "; scores are its own estimates", null));

        Map<String, Metric> budget = new LinkedHashMap<>();
        budget.put("dailySearches", Metric.of(dailySearches, "searches a day", Seo.SETTING, null));
        budget.put("usedBefore", Metric.of(usedBefore, "searches", "Leenax quota ledger, " + SeoStore.quotaDay(started) + " (Pacific)", started.toString()));
        budget.put("thisRun", Metric.counted(spent, "searches"));
        Map<String, Metric> w = new LinkedHashMap<>();
        w.put("demand", Metric.of(weights.demand(), "weight", Seo.SETTING, null));
        w.put("competition", Metric.of(weights.competition(), "weight", Seo.SETTING, null));
        w.put("gap", Metric.of(weights.gap(), "weight", Seo.SETTING, null));
        limitations.add("YouTube's API gives no search volumes, so none are shown; scores are estimates from relative signals.");
        limitations.add("Competitors are judged on public numbers only; their impressions, click-through and retention aren't public.");
        limitations.add("Keyword ideas, titles and plans come from an AI model; check them before use.");

        boolean partial = trendFiles.isEmpty() || stopped || searches.isEmpty();
        Research research = new Research(topicId, brief.title(), brief.subtopics(), languages, region, started.toString(), ideas, keywords, searches,
                competitors, coverage, plan.analysis(), trendUses, sources, budget, w, limitations, model);
        List<String> unlabelled = SeoLogic.unlabelledNumbers(JSON.valueToTree(research));
        if (!unlabelled.isEmpty()) throw new IllegalStateException("Unlabelled numbers in research: " + unlabelled);
        UUID researchId = UUID.randomUUID();
        store.insertResearch(researchId, projectId, topicId, j.id(), partial ? "PARTIAL" : "COMPLETE", research);

        // 7. the report draft
        List<Suggestion> next = new ArrayList<>(nextTopics(topicsContent, topicId, brief));
        for (Suggestion s : plan.nextVideos()) if (next.stream().noneMatch(x -> x.title().equalsIgnoreCase(s.title())) && next.size() < 8) next.add(s);
        List<String> secondary = keywords.stream().map(Keyword::text).filter(t -> !primary.contains(t)).limit(12).toList();
        Report report = new Report(topicId, brief.title(), researchId, plan.titles(), plan.recommendedId(), plan.recommendedReason(), plan.recommendedId(),
                plan.description(), primary, secondary, plan.tags(), plan.hashtags(), plan.thumbnailText(), plan.thumbnailPrompt(), plan.chapters(),
                plan.playlists(), next, plan.strategies(), model);
        String note = "Researched with " + model + (spent > 0 ? ", " + spent + " YouTube search" + (spent == 1 ? "" : "es") : "")
                + (partial ? " (partial)" : "");
        String made = writeReport(projectId, topicId, brief.title(), JSON.valueToTree(report), topicsVersion, note);
        audit.record("SEO_RESEARCHED", "project", projectId, Map.of("topic", topicId, "searches", spent, "keywords", keywords.size()));
        return keywords.size() + " keywords, " + competitors.size() + " competitor videos, " + plan.titles().size() + " titles; " + spent
                + " YouTube search" + (spent == 1 ? "" : "es") + " used" + (partial ? "; partial, see limitations" : "") + ". " + made;
    }

    private String writeReport(UUID projectId, String topicId, String title, JsonNode content, VersionRow topicsVersion, String note) {
        ResourceRow topicsResource = reviewStore.resource(topicsVersion.resourceId()).orElseThrow();
        Optional<ResourceRow> existing = reviewStore.resource(projectId, Kind.SEO, topicId, null);
        if (existing.isPresent()) {
            VersionRow newest = newest(existing.get());
            if (newest.status() == Status.IN_REVIEW) return "The report is in review, so it wasn't replaced; the research is saved.";
            if (newest.status() == Status.DRAFT) {
                reviews.replaceDraft(newest, content, note);
                return "The report draft was rewritten.";
            }
            reviews.createVersion(new Target(projectId, Kind.SEO, topicId, null, title + ": SEO report", List.of(topicsResource.id())), content,
                    Origin.AI, newest.id(), topicsVersion.id(), note);
            return "A new report version was made.";
        }
        reviews.createVersion(new Target(projectId, Kind.SEO, topicId, null, title + ": SEO report", List.of(topicsResource.id())), content,
                Origin.AI, null, topicsVersion.id(), note);
        return "The report draft was made.";
    }

    /** Up to n queries, the primary keywords of each language first, taking turns between languages. */
    static List<String> pickQueries(List<Idea> ideas, List<String> languages, int n) {
        List<String> order = List.of("PRIMARY", "QUESTION", "EXAM", "BEGINNER", "SECONDARY", "LONG_TAIL", "PRACTICAL", "REGIONAL");
        Map<String, List<Idea>> byLang = new LinkedHashMap<>();
        for (String l : languages) {
            List<Idea> l2 = new ArrayList<>(ideas.stream().filter(i -> i.language().equals(l)).toList());
            l2.sort(Comparator.comparingInt(i -> order.indexOf(i.kind())));
            byLang.put(l, l2);
        }
        Set<String> out = new LinkedHashSet<>();
        for (int round = 0; out.size() < n; round++) {
            boolean any = false;
            for (List<Idea> l : byLang.values()) {
                if (round < l.size() && out.size() < n) {
                    out.add(l.get(round).text());
                    any = true;
                }
            }
            if (!any) break;
        }
        return new ArrayList<>(out);
    }

    static Competitor competitor(VideoInfo v, String at, List<String> subtopics) {
        String text = v.title() + "\n" + v.description();
        List<String> covers = subtopics.stream().filter(s -> QaRules.covered(s, text)).toList();
        Metric views = v.views() == null ? Metric.unavailable("views", "Hidden by the channel") : Metric.of(v.views(), "views", Seo.YT_VIDEOS, at);
        Metric likes = v.likes() == null ? Metric.unavailable("likes", "Hidden by the channel") : Metric.of(v.likes(), "likes", Seo.YT_VIDEOS, at);
        Metric comments = v.comments() == null ? Metric.unavailable("comments", "Comments are off or hidden") : Metric.of(v.comments(), "comments", Seo.YT_VIDEOS, at);
        Metric duration = v.durationSeconds() == null ? Metric.unavailable("seconds", "Not given (a live stream or premiere)")
                : Metric.of(v.durationSeconds(), "seconds", Seo.YT_VIDEOS, at);
        String d = v.description() == null ? "" : v.description();
        return new Competitor(v.id(), "https://www.youtube.com/watch?v=" + v.id(), v.title(), v.channelTitle(), v.publishedAt(), duration, views, likes,
                comments, d.length() > 600 ? d.substring(0, 599) + "…" : d, v.tags() == null ? List.of() : v.tags().stream().limit(15).toList(),
                v.thumbnail(), covers, List.of(), null);
    }

    static List<VideoInfo> videosOf(JsonNode cached) {
        List<VideoInfo> out = new ArrayList<>();
        for (JsonNode v : cached.path("videos")) {
            try {
                out.add(JSON.treeToValue(v, VideoInfo.class));
            } catch (Exception e) {
                // an unreadable cached entry is skipped; it will be searched again after the cache expires
            }
        }
        return out;
    }

    /** Rising, falling or steady: the last 12 points against the 12 before. */
    static String direction(List<Double> s) {
        if (s.size() < 8) return null;
        int n = Math.min(12, s.size() / 2);
        double recent = s.subList(s.size() - n, s.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double before = s.subList(s.size() - 2 * n, s.size() - n).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (recent > before * 1.15 + 1) return "rising";
        if (recent < before * 0.85 - 1) return "falling";
        return "steady";
    }

    /** The next topics in the approved list, and the topic's prerequisites, as video ideas. */
    static List<Suggestion> nextTopics(JsonNode topics, String topicId, TopicBrief brief) {
        List<MaterialsService.TopicRow> rows = MaterialsService.topicRows(topics);
        List<Suggestion> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).id().equals(topicId)) continue;
            for (int k = i + 1; k < rows.size() && out.size() < 2; k++) {
                out.add(new Suggestion(rows.get(k).title(), "The next topic in the approved topic list"));
            }
        }
        for (String p : brief.prerequisites() == null ? List.<String>of() : brief.prerequisites()) {
            if (out.size() >= 4) break;
            out.add(new Suggestion(p, "A prerequisite of this topic: link it as “watch first”"));
        }
        return out;
    }

    private VersionRow newest(ResourceRow r) {
        List<VersionRow> vs = reviewStore.versions(r.id());
        return vs.isEmpty() ? null : vs.get(0);
    }

    static TrendsInfo info(TrendsRow t) {
        List<String> terms = new ArrayList<>();
        t.terms().forEach(x -> terms.add(x.asText()));
        return new TrendsInfo(t.id(), t.filename(), t.geo(), t.period(), t.firstDate(), t.lastDate(), terms, t.status(), t.createdBy(), t.createdAt());
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
