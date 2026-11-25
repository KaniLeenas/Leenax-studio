package com.leenax.studio.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.social.PlatformMock.Reply;
import com.leenax.studio.social.YouTubeClient.VideoInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E6: search.list and videos.list against a stand-in for YouTube's API. */
class YouTubeResearchTest {

    private PlatformMock mock;
    private YouTubeClient yt;

    @BeforeEach
    void setUp() throws Exception {
        mock = new PlatformMock();
        yt = new YouTubeClient(new SocialHttp(new ObjectMapper()), PlatformMock.props(mock.url()));
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    void searchAsksForVideosInTheLanguageAndRegionAndReturnsIdsInOrder() {
        mock.on("GET /youtube/v3/search", r -> {
            assertEquals("Bearer tok", r.header("Authorization"));
            assertEquals("normalisation in dbms", r.params().get("q"));
            assertEquals("video", r.params().get("type"));
            assertEquals("20", r.params().get("maxResults"));
            assertEquals("en", r.params().get("relevanceLanguage"));
            assertEquals("LK", r.params().get("regionCode"));
            return Reply.json(200, """
                    {"items":[{"id":{"kind":"youtube#video","videoId":"v2"}},{"id":{"kind":"youtube#video","videoId":"v1"}},
                              {"id":{"kind":"youtube#video","videoId":"v2"}},{"id":{"kind":"youtube#channel","channelId":"c"}}]}""");
        });
        assertEquals(List.of("v2", "v1"), yt.search("tok", "normalisation in dbms", "en", "LK", 20));
    }

    @Test
    void videosReadPublicNumbersAndLeaveHiddenOnesUnknown() {
        mock.on("GET /youtube/v3/videos", r -> {
            assertEquals("snippet,contentDetails,statistics", r.params().get("part"));
            return Reply.json(200, """
                    {"items":[{"id":"v1","snippet":{"title":"3NF explained","channelTitle":"DB Lab","publishedAt":"2025-03-01T10:00:00Z",
                       "description":"Third normal form","tags":["3nf","dbms"],"defaultAudioLanguage":"en",
                       "thumbnails":{"high":{"url":"https://i.ytimg.com/vi/v1/hq.jpg"}}},
                       "contentDetails":{"duration":"PT1H2M3S"},"statistics":{"viewCount":"125000","commentCount":"40"}}]}""");
        });
        List<VideoInfo> v = yt.videos("tok", List.of("v1"));
        assertEquals(1, v.size());
        VideoInfo a = v.get(0);
        assertEquals("3NF explained", a.title());
        assertEquals(3723L, a.durationSeconds());
        assertEquals(125000L, a.views());
        assertNull(a.likes(), "likes hidden by the owner stay unknown, not 0");
        assertEquals(List.of("3nf", "dbms"), a.tags());
        assertEquals("https://i.ytimg.com/vi/v1/hq.jpg", a.thumbnail());
    }

    @Test
    void durationsAreReadAsYouTubeWritesThem() {
        assertEquals(59L, YouTubeClient.isoSeconds("PT59S"));
        assertEquals(600L, YouTubeClient.isoSeconds("PT10M"));
        assertEquals(90_000L, YouTubeClient.isoSeconds("P1DT1H"));
        assertNull(YouTubeClient.isoSeconds("P0D".replace("0D", "")));
        assertNull(YouTubeClient.isoSeconds("ten minutes"));
    }

    @Test
    void anExhaustedQuotaIsReportedAsRateLimited() {
        mock.on("GET /youtube/v3/search", r -> Reply.json(403, """
                {"error":{"code":403,"message":"The request cannot be completed because you have exceeded your quota.",
                 "errors":[{"reason":"quotaExceeded","domain":"youtube.quota"}]}}"""));
        SocialException e = assertThrows(SocialException.class, () -> yt.search("tok", "x", "en", "LK", 20));
        assertEquals(SocialException.Kind.RATE_LIMITED, e.kind());
        assertTrue(e.getMessage().toLowerCase().contains("quota"), e.getMessage());
    }
}
