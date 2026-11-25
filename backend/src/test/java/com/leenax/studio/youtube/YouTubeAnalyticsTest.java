package com.leenax.studio.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.social.PlatformMock;
import com.leenax.studio.social.PlatformMock.Reply;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialHttp;
import com.leenax.studio.social.YouTubeAnalyticsClient;
import com.leenax.studio.youtube.Recommendations.Input;
import com.leenax.studio.youtube.Recommendations.Recommendation;
import com.leenax.studio.youtube.Recommendations.RetentionPoint;
import com.leenax.studio.youtube.Recommendations.Scene;
import com.leenax.studio.youtube.Recommendations.VideoStat;
import com.leenax.studio.youtube.YouTubeStore.ReachRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E9 analytics: caption files, reach reports, the Analytics and Reporting API calls, and recommendations. */
class YouTubeAnalyticsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private PlatformMock mock;
    private YouTubeAnalyticsClient api;

    @BeforeEach
    void setUp() throws Exception {
        mock = new PlatformMock();
        api = new YouTubeAnalyticsClient(new SocialHttp(mapper), PlatformMock.props(mock.url()));
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    // ---- captions from the checked render ---------------------------------------------------------------

    @Test
    void captionsComeFromTheRenderedTimelineInOrder() throws Exception {
        JsonNode doc = mapper.readTree("""
                {"tracks":[
                  {"id":"captions","kind":"text","clips":[
                    {"type":"text","caption":true,"start":4.0,"duration":2.5,"text":"second  line"},
                    {"type":"text","caption":true,"start":0.6,"duration":4.0,"text":"வணக்கம், first"},
                    {"type":"text","caption":false,"start":1,"duration":1,"text":"a title card"},
                    {"type":"text","caption":true,"start":3661.25,"duration":1.5,"text":"late"}]},
                  {"id":"scenes","kind":"video","clips":[{"type":"scene","start":0,"duration":10}]}]}
                """);
        List<Srt.Cue> cues = Srt.cues(doc);
        assertEquals(3, cues.size());
        assertEquals(4.0, cues.get(0).end(), 1e-9);   // ends where the next begins
        String srt = Srt.write(cues);
        assertTrue(srt.startsWith("1\n00:00:00,600 --> 00:00:04,000\nவணக்கம், first\n\n2\n00:00:04,000 --> 00:00:06,500\nsecond line\n\n"), srt);
        assertTrue(srt.contains("3\n01:01:01,250 --> 01:01:02,750\nlate\n"), srt);
        assertEquals("ta", Srt.language("TA"));
        assertEquals("en", Srt.language("EN"));
    }

    // ---- reach reports -----------------------------------------------------------------------------------

    @Test
    void reachReportsAreSummedPerDayAndVideoByColumnName() {
        String csv = "date,channel_id,video_id,live_or_on_demand,subscribed_status,country_code,video_thumbnail_impressions,video_thumbnail_impressions_ctr\r\n"
                + "20261120,UC1,vidA,on_demand,subscribed,LK,1000,5.0\r\n"
                + "20261120,UC1,vidA,on_demand,not_subscribed,AU,3000,1.0\r\n"
                + "20261120,UC1,vidB,on_demand,not_subscribed,LK,0,\r\n"
                + "\"20261121\",UC1,\"vidA\",on_demand,subscribed,LK,500,2.5\n";
        List<ReachRow> rows = ReachCsv.parse(csv);
        assertEquals(3, rows.size());
        ReachRow a = rows.get(0);
        assertEquals(LocalDate.of(2026, 11, 20), a.day());
        assertEquals("vidA", a.videoId());
        assertEquals(4000L, a.impressions());
        assertEquals(0.02, a.ctr(), 1e-9);   // (1000×5% + 3000×1%) / 4000
        assertNull(rows.get(1).ctr());
        assertEquals(0.025, rows.get(2).ctr(), 1e-9);
    }

    @Test
    void aReachReportWithoutItsColumnsOrWithImpossibleRatesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ReachCsv.parse("day,video,views\n20261120,a,3\n"));
        assertThrows(IllegalArgumentException.class, () -> ReachCsv.parse(
                "date,video_id,video_thumbnail_impressions,video_thumbnail_impressions_ctr\n20261120,a,10,140\n"));
        assertEquals(0, ReachCsv.parse("").size());
    }

    // ---- the APIs ------------------------------------------------------------------------------------------

    @Test
    void analyticsReportsAskForTheChannelsOwnData() {
        mock.on("GET /ytanalytics/v2/reports", r -> Reply.json(200, """
                {"kind":"youtubeAnalytics#resultTable","columnHeaders":[{"name":"video"},{"name":"views"},{"name":"averageViewPercentage"}],
                 "rows":[["vidA",120,48.5],["vidB",30,61.0]]}"""));
        YouTubeAnalyticsClient.Table t = api.report("tok", new YouTubeAnalyticsClient.Query(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28),
                "views,averageViewPercentage", "video", "video==vidA,vidB", "-views", 200));
        assertEquals(List.of("video", "views", "averageViewPercentage"), t.columns());
        assertEquals(2, t.rows().size());
        assertEquals(48.5, ((Number) t.rows().get(0).get(2)).doubleValue(), 1e-9);
        PlatformMock.Req q = mock.last("GET", "/ytanalytics/v2/reports");
        Map<String, String> p = q.params();
        assertEquals("channel==MINE", p.get("ids"));
        assertEquals("2026-10-01", p.get("startDate"));
        assertEquals("video==vidA,vidB", p.get("filters"));
        assertEquals("-views", p.get("sort"));
        assertEquals("Bearer tok", q.header("Authorization"));
    }

    @Test
    void reportingJobsAndReportsAreListedAndDownloaded() {
        mock.on("GET /ytreporting/v1/jobs", r -> Reply.json(200, "{\"jobs\":[{\"id\":\"job1\",\"reportTypeId\":\"channel_reach_basic_a1\",\"createTime\":\"2026-11-01T00:00:00Z\"}]}"));
        mock.on("POST /ytreporting/v1/jobs", r -> Reply.json(200, "{\"id\":\"job2\",\"reportTypeId\":\"channel_reach_basic_a1\",\"createTime\":\"2026-11-02T00:00:00Z\"}"));
        mock.on("GET /ytreporting/v1/jobs/job1/reports", r -> Reply.json(200, "{\"reports\":[{\"id\":\"r1\",\"startTime\":\"2026-11-20T08:00:00Z\","
                + "\"endTime\":\"2026-11-21T08:00:00Z\",\"createTime\":\"2026-11-22T10:00:00Z\",\"downloadUrl\":\"" + mock.url() + "/ytreporting/v1/media/r1\"}]}"));
        mock.on("GET /ytreporting/v1/media/r1", r -> new Reply(200, "date,video_id,video_thumbnail_impressions\n20261120,vidA,9\n", Map.of("Content-Type", "text/csv")));
        assertEquals("job1", api.jobs("tok").get(0).id());
        assertEquals("job2", api.createJob("tok", YouTubeAnalyticsClient.REACH_REPORT, "Leenax Studio reach").id());
        List<YouTubeAnalyticsClient.Report> reports = api.reports("tok", "job1", Instant.parse("2026-11-01T00:00:00Z"));
        assertEquals(1, reports.size());
        assertEquals(Instant.parse("2026-11-22T10:00:00Z"), reports.get(0).createTime());
        assertEquals("2026-11-01T00:00:00Z", mock.last("GET", "/ytreporting/v1/jobs/job1/reports").params().get("createdAfter"));
        String csv = api.download("tok", reports.get(0).downloadUrl());
        assertEquals(9L, ReachCsv.parse(csv).get(0).impressions());
        // only report addresses of the Reporting API are fetched
        assertThrows(SocialException.class, () -> api.download("tok", "https://example.com/r1"));
    }

    @Test
    void anExpiredSignInAsksForReconnection() {
        mock.on("GET /ytanalytics/v2/reports", r -> Reply.json(403,
                "{\"error\":{\"code\":403,\"message\":\"Insufficient Permission\",\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}"));
        SocialException e = assertThrows(SocialException.class, () -> api.report("tok",
                new YouTubeAnalyticsClient.Query(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), "views", null, null, null, null)));
        assertEquals(SocialException.Kind.RECONNECT, e.kind());
    }

    // ---- recommendations -------------------------------------------------------------------------------------

    static VideoStat stat(String id, String topic, long views, double pct, Long imp, Double ctr) {
        return new VideoStat(id, "Video " + id, topic, "Topic " + topic, views, views * 2.0, 120.0, pct, 1L, 3L, 1L, 0L, imp, ctr, 300.0,
                List.of("Another approved title for " + id));
    }

    static List<RetentionPoint> curve(double... watch) {
        List<RetentionPoint> out = new ArrayList<>();
        for (int i = 0; i < watch.length; i++) out.add(new RetentionPoint(i / (double) (watch.length - 1), watch[i], null));
        return out;
    }

    @Test
    void recommendationsFollowTheirRulesAndShowTheirNumbers() {
        List<VideoStat> v = List.of(
                stat("a", "t1", 2000, 55, 20000L, 0.06),
                stat("b", "t1", 1500, 52, 15000L, 0.05),
                stat("c", "t2", 900, 50, 9000L, 0.045),
                stat("d", "t3", 200, 35, 12000L, 0.008));
        // 21 points over the video: a 15-point fall from 50% to 55% of "a"
        double[] w = {1.0, 0.9, 0.85, 0.82, 0.8, 0.78, 0.76, 0.74, 0.72, 0.7, 0.68, 0.53, 0.52, 0.51, 0.5, 0.49, 0.48, 0.47, 0.46, 0.45, 0.3};
        Map<String, List<Scene>> scenes = Map.of("a", List.of(new Scene(0, 120, "Intro"), new Scene(120, 180, "Worked example"), new Scene(180, 300, "Summary")));
        Map<String, Long> traffic = new LinkedHashMap<>();
        traffic.put("YT_SEARCH", 2600L);
        traffic.put("RELATED_VIDEO", 1000L);
        traffic.put("EXT_URL", 1000L);
        Map<String, Long> terms = new LinkedHashMap<>();
        terms.put("kirchhoff voltage law", 40L);
        terms.put("video a", 30L);    // already covered by a title
        terms.put("rare", 2L);        // too few views to mean anything
        List<Recommendation> recs = Recommendations.build(new Input(v, Map.of("a", curve(w)), scenes, traffic, terms,
                List.of("Capacitors", "Inductors"), "Analytics", "Reach"));

        Recommendation best = only(recs, "BEST_SUBJECT");
        assertTrue(best.title().contains("Topic t1"), best.title());
        assertTrue(best.evidence().get(0).value().startsWith("3,500 of 4,600"), best.evidence().get(0).value());

        List<Recommendation> under = all(recs, "UNDERPERFORMING");
        assertEquals(1, under.size());
        assertEquals("d", under.get(0).videoId());
        assertTrue(under.get(0).detail().contains("few people clicked"), under.get(0).detail());

        Recommendation drop = all(recs, "DROP_OFF").stream().filter(r -> "a".equals(r.videoId())).findFirst().orElseThrow();
        assertTrue(drop.detail().contains("From 2:30 to 2:45") && drop.detail().contains("Worked example"), drop.detail());

        Recommendation title = all(recs, "TITLE_THUMBNAIL").get(0);
        assertEquals("d", title.videoId());
        assertTrue(title.detail().contains("Another approved title for d"), title.detail());

        List<Recommendation> future = all(recs, "FUTURE_TOPIC");
        assertEquals(2, future.size());
        assertTrue(future.get(0).evidence().size() == 1 && future.get(0).evidence().get(0).value().contains("kirchhoff"), future.get(0).evidence().toString());
        assertTrue(future.get(1).detail().contains("Capacitors"));

        Recommendation strategy = all(recs, "STRATEGY").get(0);
        assertTrue(strategy.detail().contains("searching") && strategy.evidence().get(0).value().contains("57%"), strategy.evidence().toString());
        for (Recommendation r : recs) {
            assertFalse(r.rule().isBlank(), "every recommendation states its rule");
            assertFalse(r.evidence().isEmpty(), "every recommendation shows its numbers: " + r.title());
        }
    }

    @Test
    void tooLittleDataGivesNoAdvice() {
        List<Recommendation> recs = Recommendations.build(new Input(List.of(stat("a", "t1", 40, 50, 300L, 0.01), stat("b", "t2", 30, 20, 100L, 0.0)),
                Map.of("a", curve(1, 0.2, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1)), Map.of(), Map.of("YT_SEARCH", 70L), Map.of(), List.of(), "A", "R"));
        assertTrue(recs.isEmpty(), recs.toString());
    }

    @Test
    void rewatchedPartsAreStrongSections() {
        double[] w = {1.0, 0.95, 0.9, 0.88, 0.86, 0.84, 0.82, 0.92, 0.95, 0.8, 0.78, 0.76};
        List<Recommendation> recs = Recommendations.build(new Input(List.of(stat("a", "t1", 500, 60, null, null)), Map.of("a", curve(w)),
                Map.of("a", List.of(new Scene(0, 150, "Theory"), new Scene(150, 300, "Demo"))), Map.of(), Map.of(), List.of(), "A", "R"));
        Recommendation strong = only(recs, "STRONG_SECTION");
        assertTrue(strong.detail().contains("Demo"), strong.detail());
    }

    static List<Recommendation> all(List<Recommendation> recs, String kind) {
        return recs.stream().filter(r -> r.kind().equals(kind)).toList();
    }

    static Recommendation only(List<Recommendation> recs, String kind) {
        List<Recommendation> l = all(recs, kind);
        assertEquals(1, l.size(), kind + ": " + l);
        return l.get(0);
    }
}
