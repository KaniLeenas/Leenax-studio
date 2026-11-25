package com.leenax.studio.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.social.PlatformMock.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading post numbers from each platform, including the fallbacks when insights aren't allowed. */
class MetricsClientsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private PlatformMock mock;
    private SocialHttp http;
    private SocialProperties props;

    @BeforeEach
    void setUp() throws Exception {
        mock = new PlatformMock();
        http = new SocialHttp(mapper);
        props = PlatformMock.props(mock.url());
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    void youtubeStatisticsAreStringsAndHiddenLikesStayUnknown() {
        mock.on("GET /youtube/v3/videos", r -> Reply.json(200, """
                {"items":[{"id":"a","statistics":{"viewCount":"1520","likeCount":"33","commentCount":"4"}},
                          {"id":"b","statistics":{"viewCount":"9","commentCount":"0"}}]}"""));
        Map<String, Counts> s = new YouTubeClient(http, props).statistics("tok", List.of("a", "b", "gone"));
        assertEquals(1520L, s.get("a").views());
        assertEquals(33L, s.get("a").likes());
        assertNull(s.get("b").likes());
        assertEquals(0L, s.get("b").comments());
        assertNull(s.get("gone"));
        assertEquals("a,b,gone", mock.last("GET", "/youtube/v3/videos").params().get("id"));
    }

    @Test
    void instagramFallsBackToBasicCountsWithoutTheInsightsPermission() {
        MetaClient meta = new MetaClient(http, props);
        mock.on("GET /v26.0/m1/insights", r -> Reply.json(200, """
                {"data":[{"name":"views","values":[{"value":900}]},{"name":"reach","values":[{"value":700}]},
                         {"name":"likes","values":[{"value":40}]},{"name":"saved","values":[{"value":6}]}]}"""));
        Counts c = meta.igCounts("m1", "t");
        assertEquals(900L, c.views());
        assertEquals(700L, c.reach());
        assertEquals(6L, c.saves());
        assertNull(c.shares());

        mock.on("GET /v26.0/m2/insights", r -> Reply.json(403, "{\"error\":{\"message\":\"(#10) permission\",\"code\":10}}"));
        mock.on("GET /v26.0/m2", r -> Reply.json(200, "{\"like_count\":12,\"comments_count\":3,\"id\":\"m2\"}"));
        Counts basic = meta.igCounts("m2", "t");
        assertNull(basic.views());
        assertEquals(12L, basic.likes());
        assertEquals(3L, basic.comments());

        mock.on("GET /v26.0/m3/insights", r -> Reply.json(429, "{\"error\":{\"message\":\"slow down\",\"code\":4}}"));
        assertEquals(SocialException.Kind.RATE_LIMITED, assertThrows(SocialException.class, () -> meta.igCounts("m3", "t")).kind());
    }

    @Test
    void facebookReelPlaysComeFromInsightsAndLikesFromSummaries() {
        mock.on("GET /v26.0/v1/video_insights", r -> Reply.json(200, """
                {"data":[{"name":"blue_reels_play_count","period":"lifetime","values":[{"value":4100}]},
                         {"name":"post_video_social_actions","period":"lifetime","values":[{"value":{"SHARE":7,"COMMENT":11}}]},
                         {"name":"post_video_likes_by_reaction_type","period":"lifetime","values":[{"value":{"REACTION_LIKE":50,"REACTION_LOVE":8}}]}]}"""));
        Counts c = new MetaClient(http, props).reelCounts("v1", "pt");
        assertEquals(4100L, c.views());
        assertEquals(58L, c.likes());
        assertEquals(11L, c.comments());
        assertEquals(7L, c.shares());
        assertEquals(0, mock.all("GET", "/v26.0/v1").size());    // no fallback needed

        mock.on("GET /v26.0/v2/video_insights", r -> Reply.json(400, "{\"error\":{\"message\":\"(#200) read_insights\",\"code\":200}}"));
        mock.on("GET /v26.0/v2", r -> Reply.json(200, "{\"likes\":{\"data\":[],\"summary\":{\"total_count\":21}},\"comments\":{\"data\":[],\"summary\":{\"total_count\":2}}}"));
        Counts basic = new MetaClient(http, props).reelCounts("v2", "pt");
        assertNull(basic.views());
        assertEquals(21L, basic.likes());
        assertEquals(2L, basic.comments());
    }

    @Test
    void tiktokVideoQueryBatchesTwentyIdsPerCall() throws Exception {
        mock.on("POST /v2/video/query/", r -> {
            try {
                JsonNode ids = mapper.readTree(r.text()).at("/filters/video_ids");
                StringBuilder vids = new StringBuilder();
                for (JsonNode id : ids) {
                    if (vids.length() > 0) vids.append(',');
                    vids.append("{\"id\":\"").append(id.asText()).append("\",\"view_count\":10,\"like_count\":2,\"comment_count\":1,\"share_count\":0}");
                }
                return Reply.json(200, "{\"data\":{\"videos\":[" + vids + "]},\"error\":{\"code\":\"ok\"}}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) ids.add("7" + i);
        Map<String, Counts> out = new TikTokClient(http, props).videoCounts("tok", ids);
        assertEquals(25, out.size());
        assertEquals(2, mock.all("POST", "/v2/video/query/").size());
        assertTrue(mock.last("POST", "/v2/video/query/").query().contains("view_count"));
        assertEquals(0L, out.get("70").shares());

        mock.on("POST /v2/video/query/", r -> Reply.json(401, "{\"error\":{\"code\":\"scope_not_authorized\",\"message\":\"no\"}}"));
        assertEquals(SocialException.Kind.RECONNECT,
                assertThrows(SocialException.class, () -> new TikTokClient(http, props).videoCounts("tok", List.of("1"))).kind());
    }

    @Test
    void scopesIncludeWhatAnalyticsNeeds() {
        assertTrue(MetaClient.SCOPES.contains("read_insights"));
        assertTrue(MetaClient.SCOPES.contains("instagram_manage_insights"));
        assertTrue(TikTokClient.SCOPES.contains("video.list"));
    }
}
