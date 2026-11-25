package com.leenax.studio.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.ReviewStore;
import com.leenax.studio.approvals.ReviewStore.ResourceRow;
import com.leenax.studio.approvals.ReviewStore.VersionRow;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.notes.MaterialsService;
import com.leenax.studio.projects.ProjectService;
import com.leenax.studio.social.SocialAccount;
import com.leenax.studio.social.SocialAccountService;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialPlatform;
import com.leenax.studio.social.YouTubeAnalyticsClient;
import com.leenax.studio.social.YouTubeAnalyticsClient.Query;
import com.leenax.studio.social.YouTubeAnalyticsClient.Table;
import com.leenax.studio.social.YouTubeClient;
import com.leenax.studio.videos.VideoStore;
import com.leenax.studio.youtube.Recommendations.RetentionPoint;
import com.leenax.studio.youtube.Recommendations.Scene;
import com.leenax.studio.youtube.Recommendations.VideoStat;
import com.leenax.studio.youtube.YouTubeStore.Published;
import com.leenax.studio.youtube.YouTubeStore.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * YouTube Analytics Dashboard (E9, module 23). Numbers come from the YouTube Analytics API for the
 * channel's own videos, and thumbnail impressions and click-through rate from the Reporting API's
 * daily reach files. Each refresh is kept as a snapshot, so a period's numbers can be looked at
 * again later exactly as they were.
 */
@Service
public class YouTubeAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(YouTubeAnalyticsService.class);
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ANALYTICS_SOURCE = "YouTube Analytics API (reports.query)";
    static final int MAX_VIDEOS = 200, MAX_RETENTION = 12, MAX_DAYS = 366;

    private final YouTubeStore store;
    private final ReviewStore reviews;
    private final VideoStore videos;
    private final ProjectService projects;
    private final SocialAccountService accounts;
    private final YouTubeAnalyticsClient api;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public YouTubeAnalyticsService(YouTubeStore store, ReviewStore reviews, VideoStore videos, ProjectService projects,
                                   SocialAccountService accounts, YouTubeAnalyticsClient api, AuditService audit, TransactionTemplate tx) {
        this.store = store;
        this.reviews = reviews;
        this.videos = videos;
        this.projects = projects;
        this.accounts = accounts;
        this.api = api;
        this.audit = audit;
        this.tx = tx;
    }

    // ---- the dashboard -----------------------------------------------------------------------------------

    public record ReachStatus(boolean started, Instant startedAt, Instant lastCheckedAt, String lastError, LocalDate lastDay, String note) {
    }

    public record VideoRow(String videoId, String url, String title, UUID resourceId, String topicId, String topicTitle, Instant publishedAt,
                           Long views, Double minutesWatched, Double averageViewDuration, Double averageViewPercentage, Long subscribersGained,
                           Long likes, Long comments, Long shares, Long impressions, Double ctr, Integer reachDays, Double durationSeconds) {
    }

    public record Totals(Long views, Double minutesWatched, Double averageViewDuration, Long subscribersGained, Long likes, Long comments,
                         Long shares, Long impressions, Double ctr) {
    }

    public record Dashboard(UUID accountId, LocalDate from, LocalDate to, Instant fetchedAt, String fetchedByName, boolean canRefresh,
                            boolean canManageReach, ReachStatus reach, Totals totals, List<VideoRow> videos, List<Map<String, Object>> daily,
                            Map<String, Long> traffic, List<Map<String, Object>> searchTerms, Map<String, List<RetentionPoint>> retention,
                            Map<String, List<Scene>> scenes, List<Recommendations.Recommendation> recommendations,
                            List<YouTubeStore.SetSummary> history, List<String> notes) {
    }

    /** The newest snapshot of a period (nothing is fetched here; Refresh does that). */
    public Dashboard dashboard(UUID projectId, UUID accountId, LocalDate from, LocalDate to) {
        projects.requireAccess(projectId, false, null);
        Period p = period(from, to);
        SocialAccount a = youtube(accountId);
        List<Snapshot> set = store.latestSet(projectId, a.getId(), p.from(), p.to());
        return build(projectId, a, p, set);
    }

    /** A snapshot set taken at a given time, as it was. */
    public Dashboard snapshotSet(UUID projectId, UUID accountId, Instant fetchedAt) {
        projects.requireAccess(projectId, false, null);
        SocialAccount a = youtube(accountId);
        List<Snapshot> set = store.set(projectId, a.getId(), fetchedAt);
        if (set.isEmpty()) throw ApiException.notFound("Snapshot");
        return build(projectId, a, new Period(set.get(0).startDate(), set.get(0).endDate()), set);
    }

    public List<YouTubeStore.SetSummary> history(UUID projectId, LocalDate from, LocalDate to) {
        projects.requireAccess(projectId, false, null);
        return store.sets(projectId, from, to);
    }

    record Period(LocalDate from, LocalDate to) {
    }

    static Period period(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate t = to == null ? today.minusDays(1) : to;
        LocalDate f = from == null ? t.minusDays(27) : from;
        if (f.isAfter(t)) throw ApiException.badRequest("The start date is after the end date");
        if (t.isAfter(today)) throw ApiException.badRequest("The end date is in the future");
        if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS) throw ApiException.badRequest("Choose a period of at most a year");
        if (f.isBefore(LocalDate.of(2005, 2, 14))) throw ApiException.badRequest("YouTube has no data before 2005");
        return new Period(f, t);
    }

    // ---- refresh: query YouTube, keep a snapshot --------------------------------------------------------

    public Dashboard refresh(UUID projectId, UUID accountId, LocalDate from, LocalDate to) {
        projects.requireAccess(projectId, false, null);
        Period p = period(from, to);
        SocialAccount a = youtube(accountId);
        if (!YouTubeClient.granted(a.getScopes(), YouTubeClient.SCOPE_ANALYTICS)) {
            throw new ApiException(ErrorCode.CONFLICT, a.getDisplayName() + " was connected before analytics were supported. "
                    + "Reconnect it in Publishing → Accounts and allow YouTube Analytics.");
        }
        List<Published> pubs = store.published(projectId).stream().filter(x -> a.getId().equals(x.accountId())).toList();
        if (pubs.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "No video of this project has been published on " + a.getDisplayName() + " yet.");
        }
        List<String> ids = pubs.stream().map(Published::videoId).distinct().limit(MAX_VIDEOS).toList();
        String filter = "video==" + String.join(",", ids);
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<Object[]> parts = new ArrayList<>();   // {kind, videoId, data}
        try {
            String token = accounts.accessToken(a.getId());
            parts.add(part("BASIC", null, api.report(token, new Query(p.from(), p.to(),
                    "views,estimatedMinutesWatched,averageViewDuration,averageViewPercentage,subscribersGained,likes,comments,shares",
                    "video", filter, "-views", MAX_VIDEOS))));
            parts.add(part("CHANNEL", null, api.report(token, new Query(p.from(), p.to(),
                    "views,estimatedMinutesWatched,subscribersGained,likes,comments,shares", "day", filter, "day", null))));
            parts.add(part("TRAFFIC", null, api.report(token, new Query(p.from(), p.to(), "views,estimatedMinutesWatched",
                    "insightTrafficSourceType", filter, "-views", null))));
            parts.add(part("SEARCH_TERMS", null, api.report(token, new Query(p.from(), p.to(), "views",
                    "insightTrafficSourceDetail", filter + ";insightTrafficSourceType==YT_SEARCH", "-views", 25))));
            // retention is asked one video at a time; the videos with the most views first
            Table basic = api.report(token, new Query(p.from(), p.to(), "views", "video", filter, "-views", MAX_RETENTION));
            int vi = basic.column("video");
            for (List<Object> row : basic.rows()) {
                String id = String.valueOf(row.get(vi));
                Table t;
                try {
                    t = api.report(token, new Query(p.from(), p.to(), "audienceWatchRatio,relativeRetentionPerformance", "elapsedVideoTimeRatio",
                            "video==" + id, null, null));
                } catch (SocialException e) {
                    if (e.kind() != SocialException.Kind.REJECTED) throw e;
                    // relative retention isn't offered for every video; the watch ratio alone still shows drop-offs
                    t = api.report(token, new Query(p.from(), p.to(), "audienceWatchRatio", "elapsedVideoTimeRatio", "video==" + id, null, null));
                }
                parts.add(part("RETENTION", id, t));
            }
        } catch (SocialException e) {
            throw EduPublishService.fromSocial(e);
        }
        UUID projectIdFinal = projectId;
        tx.executeWithoutResult(s -> {
            for (Object[] part : parts) {
                store.addSnapshot(UUID.randomUUID(), projectIdFinal, a.getId(), (String) part[0], (String) part[1], p.from(), p.to(),
                        (String) part[2], at);
            }
        });
        audit.record("YOUTUBE_ANALYTICS_SNAPSHOT", "social_account", a.getId(), Map.of("project", projectId.toString(),
                "from", p.from().toString(), "to", p.to().toString(), "parts", parts.size()));
        return build(projectId, a, p, store.set(projectId, a.getId(), at));
    }

    static Object[] part(String kind, String videoId, Table t) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", ANALYTICS_SOURCE);
        data.put("columns", t.columns());
        data.put("rows", t.rows());
        try {
            return new Object[]{kind, videoId, JSON.writeValueAsString(data)};
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- assembling the dashboard --------------------------------------------------------------------------

    Dashboard build(UUID projectId, SocialAccount a, Period p, List<Snapshot> set) {
        List<String> notes = new ArrayList<>();
        Map<String, Published> pubByVideo = new LinkedHashMap<>();
        for (Published x : store.published(projectId)) if (a.getId().equals(x.accountId())) pubByVideo.putIfAbsent(x.videoId(), x);
        Map<String, String> topicTitles = topicTitles(projectId);
        Map<String, Info> info = new HashMap<>();
        for (Published x : pubByVideo.values()) info.put(x.videoId(), info(projectId, x));

        Map<String, YouTubeStore.Reach> reach = new HashMap<>();
        for (YouTubeStore.Reach r : store.reach(a.getId(), p.from(), p.to())) reach.put(r.videoId(), r);
        Optional<YouTubeStore.ReportingJob> job = store.reportingJob(a.getId());
        Optional<LocalDate> lastDay = store.lastReachDay(a.getId());
        String reachNote = job.isEmpty() ? "Reach reports aren't turned on for this channel, so impressions and click-through rate aren't available."
                : lastDay.isEmpty() ? "Reach reports are on. YouTube makes the first file about two days after they are turned on."
                : lastDay.get().isBefore(p.to()) ? "Impressions and click-through rate are imported up to " + lastDay.get() + "." : null;
        ReachStatus rs = new ReachStatus(job.isPresent(), job.map(YouTubeStore.ReportingJob::createdAt).orElse(null),
                job.map(YouTubeStore.ReportingJob::lastCheckedAt).orElse(null), job.map(YouTubeStore.ReportingJob::lastError).orElse(null),
                lastDay.orElse(null), reachNote);

        Snapshot basic = find(set, "BASIC", null), channel = find(set, "CHANNEL", null), traffic = find(set, "TRAFFIC", null),
                terms = find(set, "SEARCH_TERMS", null);
        List<VideoRow> rows = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        if (basic != null) {
            for (Map<String, Object> r : records(basic.data())) {
                String id = String.valueOf(r.get("video"));
                listed.add(id);
                rows.add(row(id, pubByVideo.get(id), topicTitles, info.get(id), r, reach.get(id)));
            }
        }
        for (Published x : pubByVideo.values()) {
            // published but with no views in the period: YouTube leaves them out of the report
            if (!listed.contains(x.videoId()) && basic != null) rows.add(row(x.videoId(), x, topicTitles, info.get(x.videoId()), Map.of(), reach.get(x.videoId())));
        }
        Totals totals = totals(rows, basic != null);
        List<Map<String, Object>> daily = channel == null ? List.of() : records(channel.data());
        Map<String, Long> trafficViews = new LinkedHashMap<>();
        if (traffic != null) for (Map<String, Object> r : records(traffic.data())) trafficViews.put(String.valueOf(r.get("insightTrafficSourceType")), lng(r.get("views")));
        List<Map<String, Object>> searchTerms = terms == null ? List.of() : records(terms.data());
        Map<String, Long> termViews = new LinkedHashMap<>();
        for (Map<String, Object> r : searchTerms) termViews.put(String.valueOf(r.get("insightTrafficSourceDetail")), lng(r.get("views")));
        Map<String, List<RetentionPoint>> retention = new LinkedHashMap<>();
        Map<String, List<Scene>> scenes = new LinkedHashMap<>();
        for (Snapshot s : set) {
            if (!"RETENTION".equals(s.kind()) || s.videoId() == null) continue;
            List<RetentionPoint> pts = new ArrayList<>();
            for (Map<String, Object> r : records(s.data())) {
                Double ratio = dbl(r.get("elapsedVideoTimeRatio")), watch = dbl(r.get("audienceWatchRatio"));
                if (ratio != null && watch != null) pts.add(new RetentionPoint(ratio, watch, dbl(r.get("relativeRetentionPerformance"))));
            }
            pts.sort((x, y) -> Double.compare(x.ratio(), y.ratio()));
            retention.put(s.videoId(), pts);
            Info in = info.get(s.videoId());
            if (in != null) scenes.put(s.videoId(), in.scenes());
        }
        if (set.isEmpty()) notes.add("No snapshot of this period yet. Refresh to fetch it from YouTube.");
        else notes.add("YouTube's numbers for the last two or three days can still change.");
        if (reachNote != null) notes.add(reachNote);

        List<VideoStat> stats = rows.stream().map(r -> new VideoStat(r.videoId(), r.title(), r.topicId(), r.topicTitle(), r.views(), r.minutesWatched(),
                r.averageViewDuration(), r.averageViewPercentage(), r.subscribersGained(), r.likes(), r.comments(), r.shares(), r.impressions(), r.ctr(),
                r.durationSeconds(), info.get(r.videoId()) == null ? List.of() : info.get(r.videoId()).otherTitles())).toList();
        String reachSource = "YouTube Reporting API reach report (" + YouTubeAnalyticsClient.REACH_REPORT + "), imported daily";
        List<Recommendations.Recommendation> recs = set.isEmpty() ? List.of()
                : Recommendations.build(new Recommendations.Input(stats, retention, scenes, trafficViews, termViews, unpublishedTopics(projectId, pubByVideo),
                ANALYTICS_SOURCE, reachSource));
        Instant fetched = set.isEmpty() ? null : set.get(0).fetchedAt();
        String by = set.isEmpty() ? null : set.get(0).createdByName();
        return new Dashboard(a.getId(), p.from(), p.to(), fetched, by, YouTubeClient.granted(a.getScopes(), YouTubeClient.SCOPE_ANALYTICS),
                projects.isOwner(projectId), rs, totals, rows, daily, trafficViews, searchTerms, retention, scenes, recs,
                store.sets(projectId, null, null).stream().filter(x -> a.getId().equals(x.accountId())).limit(20).toList(), notes);
    }

    static VideoRow row(String id, Published pub, Map<String, String> topics, Info info, Map<String, Object> r, YouTubeStore.Reach reach) {
        return new VideoRow(id, "https://www.youtube.com/watch?v=" + id, pub == null ? id : pub.title(), pub == null ? null : pub.resourceId(),
                pub == null ? null : pub.topicId(), pub == null ? null : topics.get(pub.topicId()), pub == null ? null : pub.publishedAt(),
                lngOrNull(r.get("views")), dbl(r.get("estimatedMinutesWatched")), dbl(r.get("averageViewDuration")), dbl(r.get("averageViewPercentage")),
                lngOrNull(r.get("subscribersGained")), lngOrNull(r.get("likes")), lngOrNull(r.get("comments")), lngOrNull(r.get("shares")),
                reach == null ? null : reach.impressions(), reach == null ? null : reach.ctr(), reach == null ? null : reach.days(),
                info == null ? null : info.duration());
    }

    static Totals totals(List<VideoRow> rows, boolean have) {
        if (!have) return new Totals(null, null, null, null, null, null, null, null, null);
        long views = 0, subs = 0, likes = 0, comments = 0, shares = 0, imp = 0;
        double minutes = 0, clicks = 0;
        boolean anyReach = false;
        for (VideoRow r : rows) {
            views += n(r.views());
            subs += n(r.subscribersGained());
            likes += n(r.likes());
            comments += n(r.comments());
            shares += n(r.shares());
            minutes += r.minutesWatched() == null ? 0 : r.minutesWatched();
            if (r.impressions() != null) {
                anyReach = true;
                imp += r.impressions();
                clicks += r.ctr() == null ? 0 : r.ctr() * r.impressions();
            }
        }
        // average view duration over all views: minutes watched divided by views
        Double avd = views == 0 ? null : minutes * 60 / views;
        return new Totals(views, minutes, avd, subs, likes, comments, shares, anyReach ? imp : null, anyReach && imp > 0 ? clicks / imp : null);
    }

    static long n(Long x) {
        return x == null ? 0 : x;
    }

    /** A published video's approved content: its scenes (from the checked render), length and other approved titles. */
    record Info(List<Scene> scenes, Double duration, List<String> otherTitles) {
    }

    Info info(UUID projectId, Published x) {
        List<Scene> scenes = new ArrayList<>();
        Double duration = null;
        try {
            VersionRow v = reviews.version(x.versionId()).orElse(null);
            if (v != null) {
                Optional<VideoStore.RenderRow> render = videos.renders(x.resourceId()).stream()
                        .filter(r -> r.contentHash().equals(v.contentHash()) && "COMPLETED".equals(r.status())).findFirst();
                JsonNode doc = render.flatMap(r -> store.renderDocument(r.renderJobId())).orElse(null);
                if (doc != null) {
                    double end = 0;
                    for (JsonNode track : doc.path("tracks")) {
                        for (JsonNode clip : track.path("clips")) {
                            double s = clip.path("start").asDouble(), e = s + clip.path("duration").asDouble();
                            end = Math.max(end, e);
                            if ("scene".equals(clip.path("type").asText())) {
                                scenes.add(new Scene(s, e, clip.path("scene").path("title").asText(null)));
                            }
                        }
                    }
                    duration = end > 0 ? end : null;
                }
            }
        } catch (RuntimeException e) {
            log.debug("No scene timing for {}: {}", x.videoId(), e.getMessage());
        }
        List<String> others = new ArrayList<>();
        videos.approved(projectId, Kind.SEO, x.topicId(), null).flatMap(videos::content).ifPresent(c -> {
            for (JsonNode t : MaterialsService.read(c).path("titles")) {
                String text = t.path("text").asText("").strip();
                if (!text.isEmpty() && !text.equals(x.title())) others.add(text);
            }
        });
        return new Info(scenes, duration, others);
    }

    Map<String, String> topicTitles(UUID projectId) {
        Map<String, String> out = new HashMap<>();
        reviews.resource(projectId, Kind.TOPICS).flatMap(r -> reviews.approved(r.id())).flatMap(v -> reviews.version(v.id())).ifPresent(v -> {
            for (MaterialsService.TopicRow t : MaterialsService.topicRows(MaterialsService.read(v.content()))) out.put(t.id(), t.title());
        });
        return out;
    }

    List<String> unpublishedTopics(UUID projectId, Map<String, Published> pubs) {
        Set<String> done = new HashSet<>();
        pubs.values().forEach(x -> done.add(x.topicId()));
        List<String> out = new ArrayList<>();
        reviews.resource(projectId, Kind.TOPICS).flatMap(r -> reviews.approved(r.id())).flatMap(v -> reviews.version(v.id())).ifPresent(v -> {
            for (MaterialsService.TopicRow t : MaterialsService.topicRows(MaterialsService.read(v.content()))) {
                if (!done.contains(t.id()) && t.depth() > 0) out.add(t.title());
            }
        });
        return out;
    }

    // ---- reach reports (Reporting API) ---------------------------------------------------------------------

    /** Turns on the daily reach report for a channel (or finds the one already on). Owner or administrator. */
    public ReachStatus startReach(UUID projectId, UUID accountId) {
        projects.requireAccess(projectId, false, null);
        if (!projects.isOwner(projectId)) throw new ApiException(ErrorCode.FORBIDDEN, "Only the project owner or an administrator can turn on reach reports");
        SocialAccount a = youtube(accountId);
        if (!YouTubeClient.granted(a.getScopes(), YouTubeClient.SCOPE_ANALYTICS)) throw EduPublishService.reconnect(a);
        try {
            String token = accounts.accessToken(a.getId());
            YouTubeAnalyticsClient.ReportingJob job = api.jobs(token).stream()
                    .filter(j -> YouTubeAnalyticsClient.REACH_REPORT.equals(j.reportTypeId())).findFirst()
                    .orElseGet(() -> api.createJob(token, YouTubeAnalyticsClient.REACH_REPORT, "Leenax Studio reach"));
            store.saveReportingJob(a.getId(), job.reportTypeId(), job.id());
            audit.record("YOUTUBE_REACH_STARTED", "social_account", a.getId(), Map.of("job", job.id()));
        } catch (SocialException e) {
            throw EduPublishService.fromSocial(e);
        }
        return reachStatus(a.getId());
    }

    ReachStatus reachStatus(UUID accountId) {
        Optional<YouTubeStore.ReportingJob> job = store.reportingJob(accountId);
        return new ReachStatus(job.isPresent(), job.map(YouTubeStore.ReportingJob::createdAt).orElse(null),
                job.map(YouTubeStore.ReportingJob::lastCheckedAt).orElse(null), job.map(YouTubeStore.ReportingJob::lastError).orElse(null),
                store.lastReachDay(accountId).orElse(null), null);
    }

    /** Imports a channel's new reach files now. */
    public ReachStatus importReachNow(UUID projectId, UUID accountId) {
        projects.requireAccess(projectId, false, null);
        SocialAccount a = youtube(accountId);
        if (store.reportingJob(a.getId()).isEmpty()) throw new ApiException(ErrorCode.CONFLICT, "Turn on reach reports for this channel first");
        int n = importReach(a.getId());
        log.info("Imported {} reach report(s) for {}", n, a.getId());
        return reachStatus(a.getId());
    }

    /**
     * Downloads every reach file not imported yet (YouTube keeps them for 60 days, and sends a
     * corrected file for a day as a new report). Returns how many were imported. Errors are kept
     * on the job and reported; the next daily run tries again.
     */
    public int importReach(UUID accountId) {
        YouTubeStore.ReportingJob job = store.reportingJob(accountId).orElse(null);
        if (job == null) return 0;
        int imported = 0;
        try {
            String token = accounts.accessToken(accountId);
            List<YouTubeAnalyticsClient.Report> reports = new ArrayList<>(api.reports(token, job.jobId(), null));
            // oldest first, so a backfill (created later) replaces what it corrects
            reports.sort((x, y) -> compare(x.createTime(), y.createTime()));
            for (YouTubeAnalyticsClient.Report r : reports) {
                if (r.downloadUrl() == null || store.haveReport(accountId, r.id())) continue;
                String csv = api.download(token, r.downloadUrl());
                List<YouTubeStore.ReachRow> rows = ReachCsv.parse(csv);
                tx.executeWithoutResult(s -> store.saveReport(accountId, r.id(), r.startTime() == null ? Instant.EPOCH : r.startTime(),
                        r.endTime() == null ? Instant.EPOCH : r.endTime(), r.createTime() == null ? Instant.now() : r.createTime(), rows));
                imported++;
            }
            store.checked(accountId, null);
        } catch (SocialException e) {
            store.checked(accountId, clip(e.getMessage()));
            if (e.kind() == SocialException.Kind.RECONNECT) accounts.flagReconnect(accountId, e.getMessage());
            throw EduPublishService.fromSocial(e);
        } catch (IllegalArgumentException e) {
            store.checked(accountId, clip("A reach report couldn't be read: " + e.getMessage()));
            throw new ApiException(ErrorCode.CONFLICT, "A reach report couldn't be read: " + e.getMessage());
        }
        return imported;
    }

    /** Every channel with reach reports on: the daily import. */
    public void importAll() {
        for (YouTubeStore.ReportingJob j : store.reportingJobs()) {
            try {
                importReach(j.accountId());
            } catch (RuntimeException e) {
                log.warn("Reach import for {} failed: {}", j.accountId(), e.getMessage());
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    SocialAccount youtube(UUID id) {
        if (id == null) throw ApiException.badRequest("Choose a YouTube channel");
        SocialAccount a = accounts.find(id);
        if (a.getPlatform() != SocialPlatform.YOUTUBE) throw ApiException.badRequest("Choose a YouTube channel");
        return a;
    }

    static Snapshot find(List<Snapshot> set, String kind, String videoId) {
        for (Snapshot s : set) if (s.kind().equals(kind) && (videoId == null || videoId.equals(s.videoId()))) return s;
        return null;
    }

    /** A stored table as one map per row, keyed by column name. */
    static List<Map<String, Object>> records(JsonNode data) {
        List<String> cols = new ArrayList<>();
        data.path("columns").forEach(c -> cols.add(c.asText()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode row : data.path("rows")) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < cols.size() && i < row.size(); i++) {
                JsonNode v = row.get(i);
                m.put(cols.get(i), v.isNumber() ? v.numberValue() : v.isNull() ? null : v.asText());
            }
            out.add(m);
        }
        return out;
    }

    static Double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    static Long lngOrNull(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    static long lng(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    static int compare(Instant a, Instant b) {
        return a == null ? (b == null ? 0 : -1) : b == null ? 1 : a.compareTo(b);
    }

    static String clip(String s) {
        return s == null ? null : s.length() > 480 ? s.substring(0, 480) : s;
    }
}
