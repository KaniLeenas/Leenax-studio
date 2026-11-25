package com.leenax.studio.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.social.MetaClient;
import com.leenax.studio.social.PlatformMock;
import com.leenax.studio.social.PlatformMock.Reply;
import com.leenax.studio.social.SocialAccount;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialHttp;
import com.leenax.studio.social.SocialPlatform;
import com.leenax.studio.social.SocialProperties;
import com.leenax.studio.social.TikTokClient;
import com.leenax.studio.social.YouTubeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each publisher's full flow (upload → processing → live) against a mock of the platform API. */
class PublishersTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private PlatformMock mock;
    private SocialHttp http;
    private SocialProperties props;
    private Path video;
    private final List<String> checkpoints = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mock = new PlatformMock();
        http = new SocialHttp(mapper);
        props = PlatformMock.props(mock.url());
        video = Files.createTempFile("leenax-pub-", ".mp4");
        Files.write(video, "0123456789".getBytes(StandardCharsets.US_ASCII));
    }

    @AfterEach
    void tearDown() throws Exception {
        mock.close();
        Files.deleteIfExists(video);
    }

    private Job job(SocialAccount account, Map<String, Object> settings, Map<String, Object> state) {
        return new Job(account, settings, state, 10, "video/mp4", 30_000L, () -> "tok", () -> video, null,
                (s, step, p) -> checkpoints.add(step));
    }

    // ---- YouTube ------------------------------------------------------------------

    @Test
    void youtubeUploadsThenReportsThePrivateLock() throws Exception {
        String session = mock.url() + "/session/1";
        mock.on("POST /upload/youtube/v3/videos", r -> Reply.json(200, "").with("Location", session));
        mock.on("PUT /session/1", r -> Reply.json(200, "{\"id\":\"yt1\"}"));
        AtomicInteger polls = new AtomicInteger();
        mock.on("GET /youtube/v3/videos", r -> Reply.json(200, polls.incrementAndGet() == 1
                ? "{\"items\":[{\"status\":{\"uploadStatus\":\"uploaded\",\"privacyStatus\":\"private\"}}]}"
                : "{\"items\":[{\"status\":{\"uploadStatus\":\"processed\",\"privacyStatus\":\"private\"}}]}"));
        YouTubePublisher p = new YouTubePublisher(new YouTubeClient(http, props));
        Map<String, Object> state = new HashMap<>();
        Job j = job(new SocialAccount(SocialPlatform.YOUTUBE, "UC1", "Channel"),
                Map.of("title", "Trip", "description", "d", "tags", List.of("a"), "categoryId", "19",
                        "privacy", "public", "madeForKids", false, "aiGenerated", true), state);

        Outcome first = p.start(j);
        assertInstanceOf(Outcome.Wait.class, first);
        assertEquals(PostTarget.Status.PROCESSING, ((Outcome.Wait) first).status());
        assertEquals("yt1", state.get("videoId"));
        assertNull(state.get("sessionUrl"));
        JsonNode meta = mapper.readTree(mock.last("POST", "/upload/youtube/v3/videos").text());
        assertEquals("public", meta.at("/status/privacyStatus").asText());
        assertEquals(true, meta.at("/status/containsSyntheticMedia").asBoolean());
        assertEquals(false, meta.at("/status/selfDeclaredMadeForKids").asBoolean(true));
        assertEquals("19", meta.at("/snippet/categoryId").asText());

        assertInstanceOf(Outcome.Wait.class, p.poll(j));
        Outcome.Published done = assertInstanceOf(Outcome.Published.class, p.poll(j));
        assertEquals("https://www.youtube.com/watch?v=yt1", done.url());
        assertEquals(YouTubePublisher.PRIVATE_LOCK_WARNING, done.warning());
        assertTrue(checkpoints.contains("Uploaded to YouTube"));
    }

    @Test
    void youtubeRetryAfterACrashResumesTheSameSession() {
        String session = mock.url() + "/session/2";
        mock.on("PUT /session/2", r -> "bytes */10".equals(r.header("Content-Range"))
                ? Reply.json(308, "").with("Range", "bytes=0-6")
                : Reply.json(201, "{\"id\":\"yt2\"}"));
        Map<String, Object> state = new HashMap<>(Map.of("sessionUrl", session));
        YouTubePublisher p = new YouTubePublisher(new YouTubeClient(http, props));
        p.start(job(new SocialAccount(SocialPlatform.YOUTUBE, "UC1", "Channel"),
                Map.of("title", "T", "privacy", "private", "madeForKids", false), state));
        assertEquals("yt2", state.get("videoId"));
        assertEquals(0, mock.all("POST", "/upload/youtube/v3/videos").size());     // no second upload session
        assertEquals("789", mock.last("PUT", "/session/2").text());
    }

    @Test
    void youtubeRejectionFailsThePost() {
        mock.on("GET /youtube/v3/videos", r -> Reply.json(200,
                "{\"items\":[{\"status\":{\"uploadStatus\":\"rejected\",\"rejectionReason\":\"duplicate\"}}]}"));
        YouTubePublisher p = new YouTubePublisher(new YouTubeClient(http, props));
        SocialException e = assertThrows(SocialException.class, () -> p.poll(job(new SocialAccount(SocialPlatform.YOUTUBE, "UC1", "C"),
                Map.of("privacy", "public"), new HashMap<>(Map.of("videoId", "x")))));
        assertEquals(SocialException.Kind.REJECTED, e.kind());
        assertTrue(e.getMessage().contains("duplicate"));
    }

    // ---- Facebook ----------------------------------------------------------------------

    @Test
    void facebookReelGoesStartUploadFinishThenWaitsForPublishing() {
        mock.on("POST /v26.0/p1/video_reels", r -> "start".equals(r.form().get("upload_phase"))
                ? Reply.json(200, "{\"video_id\":\"v1\",\"upload_url\":\"" + mock.url() + "/rupload/video-upload/v26.0/v1\"}")
                : Reply.json(200, "{\"success\":true}"));
        mock.on("POST /rupload/video-upload/v26.0/v1", r -> Reply.json(200, "{\"success\":true}"));
        AtomicInteger polls = new AtomicInteger();
        mock.on("GET /v26.0/v1", r -> Reply.json(200, polls.incrementAndGet() == 1
                ? "{\"status\":{\"video_status\":\"processing\",\"publishing_phase\":{\"status\":\"not_started\"}}}"
                : "{\"status\":{\"video_status\":\"ready\",\"publishing_phase\":{\"status\":\"complete\"}},\"permalink_url\":\"/reel/v1\"}"));
        FacebookPublisher p = new FacebookPublisher(new MetaClient(http, props));
        Map<String, Object> state = new HashMap<>();
        Job j = job(new SocialAccount(SocialPlatform.FACEBOOK, "p1", "My Page"), Map.of("description", "Hi"), state);
        assertInstanceOf(Outcome.Wait.class, p.start(j));
        assertEquals(true, state.get("uploaded"));
        assertEquals(true, state.get("finished"));
        assertEquals(2, mock.all("POST", "/v26.0/p1/video_reels").size());
        assertInstanceOf(Outcome.Wait.class, p.poll(j));
        Outcome.Published done = assertInstanceOf(Outcome.Published.class, p.poll(j));
        assertEquals("https://www.facebook.com/reel/v1", done.url());

        // A second start after finishing must not post again.
        p.start(j);
        assertEquals(2, mock.all("POST", "/v26.0/p1/video_reels").size());
    }

    @Test
    void facebookProcessingErrorIsReported() {
        mock.on("GET /v26.0/v2", r -> Reply.json(200,
                "{\"status\":{\"video_status\":\"error\",\"processing_phase\":{\"status\":\"error\",\"errors\":[{\"message\":\"Reels duration must be between 3 and 90 seconds.\"}]}}}"));
        FacebookPublisher p = new FacebookPublisher(new MetaClient(http, props));
        SocialException e = assertThrows(SocialException.class, () -> p.poll(job(new SocialAccount(SocialPlatform.FACEBOOK, "p1", "P"),
                Map.of(), new HashMap<>(Map.of("videoId", "v2")))));
        assertTrue(e.getMessage().contains("90 seconds"));
    }

    // ---- Instagram -------------------------------------------------------------------------

    @Test
    void instagramReelPublishesWhenTheContainerIsFinished() {
        mock.on("GET /v26.0/ig1/content_publishing_limit", r -> Reply.json(200,
                "{\"data\":[{\"quota_usage\":3,\"config\":{\"quota_total\":100}}]}"));
        mock.on("POST /v26.0/ig1/media", r -> Reply.json(200, "{\"id\":\"c1\"}"));
        mock.on("POST /rupload/ig-api-upload/v26.0/c1", r -> Reply.json(200, "{\"success\":true}"));
        mock.on("GET /v26.0/c1", r -> Reply.json(200, "{\"status_code\":\"FINISHED\"}"));
        mock.on("POST /v26.0/ig1/media_publish", r -> Reply.json(200, "{\"id\":\"m1\"}"));
        mock.on("GET /v26.0/m1", r -> Reply.json(200, "{\"permalink\":\"https://www.instagram.com/reel/abc/\"}"));
        InstagramPublisher p = new InstagramPublisher(new MetaClient(http, props));
        Map<String, Object> state = new HashMap<>();
        Job j = job(new SocialAccount(SocialPlatform.INSTAGRAM, "ig1", "@me"),
                Map.of("caption", "Hello #travel", "shareToFeed", true, "coverSeconds", 1.5), state);
        assertInstanceOf(Outcome.Wait.class, p.start(j));
        Map<String, String> form = mock.last("POST", "/v26.0/ig1/media").form();
        assertEquals("REELS", form.get("media_type"));
        assertEquals("resumable", form.get("upload_type"));
        assertEquals("1500", form.get("thumb_offset"));
        assertEquals("Hello #travel", form.get("caption"));
        assertEquals("10", mock.last("POST", "/rupload/ig-api-upload/v26.0/c1").header("file_size"));

        Outcome.Published done = assertInstanceOf(Outcome.Published.class, p.poll(j));
        assertEquals("m1", done.remoteId());
        assertEquals("https://www.instagram.com/reel/abc/", done.url());
        assertEquals("c1", mock.last("POST", "/v26.0/ig1/media_publish").form().get("creation_id"));
    }

    @Test
    void instagramStopsAtTheDailyPublishingLimit() {
        mock.on("GET /v26.0/ig1/content_publishing_limit", r -> Reply.json(200,
                "{\"data\":[{\"quota_usage\":100,\"config\":{\"quota_total\":100}}]}"));
        InstagramPublisher p = new InstagramPublisher(new MetaClient(http, props));
        SocialException e = assertThrows(SocialException.class, () -> p.start(job(new SocialAccount(SocialPlatform.INSTAGRAM, "ig1", "@me"),
                Map.of(), new HashMap<>())));
        assertEquals(SocialException.Kind.RATE_LIMITED, e.kind());
        assertEquals(0, mock.all("POST", "/v26.0/ig1/media").size());
    }

    @Test
    void expiredInstagramContainerStartsOver() {
        mock.on("GET /v26.0/c9", r -> Reply.json(200, "{\"status_code\":\"EXPIRED\"}"));
        InstagramPublisher p = new InstagramPublisher(new MetaClient(http, props));
        Map<String, Object> state = new HashMap<>(Map.of("containerId", "c9", "uploaded", true));
        Outcome.Wait w = assertInstanceOf(Outcome.Wait.class, p.poll(job(new SocialAccount(SocialPlatform.INSTAGRAM, "ig1", "@me"), Map.of(), state)));
        assertEquals(PostTarget.Status.SCHEDULED, w.status());
        assertTrue(state.isEmpty());
    }

    // ---- TikTok ---------------------------------------------------------------------------------

    private void creator(String options) {
        mock.on("POST /v2/post/publish/creator_info/query/", r -> Reply.json(200,
                "{\"data\":{\"creator_username\":\"kani\",\"privacy_level_options\":" + options
                        + ",\"comment_disabled\":true,\"duet_disabled\":false,\"stitch_disabled\":false,\"max_video_post_duration_sec\":600},"
                        + "\"error\":{\"code\":\"ok\"}}"));
    }

    @Test
    void tiktokDirectPostChecksCreatorInfoThenLinksThePublicPost() throws Exception {
        creator("[\"PUBLIC_TO_EVERYONE\",\"SELF_ONLY\"]");
        mock.on("POST /v2/post/publish/video/init/", r -> Reply.json(200,
                "{\"data\":{\"publish_id\":\"pb1\",\"upload_url\":\"" + mock.url() + "/tt-up\"},\"error\":{\"code\":\"ok\"}}"));
        mock.on("PUT /tt-up", r -> Reply.json(201, ""));
        mock.on("POST /v2/post/publish/status/fetch/", r -> Reply.json(200,
                "{\"data\":{\"status\":\"PUBLISH_COMPLETE\",\"publicaly_available_post_id\":[7300000000000000001]},\"error\":{\"code\":\"ok\"}}"));
        TikTokPublisher p = new TikTokPublisher(new TikTokClient(http, props));
        Map<String, Object> state = new HashMap<>();
        Job j = job(new SocialAccount(SocialPlatform.TIKTOK, "open1", "Kani"),
                Map.of("mode", "DIRECT", "caption", "hi #fyp", "privacy", "PUBLIC_TO_EVERYONE", "allowComment", true,
                        "allowDuet", true, "allowStitch", false, "aiGenerated", true, "musicConsent", true), state);
        assertInstanceOf(Outcome.Wait.class, p.start(j));
        JsonNode info = mapper.readTree(mock.last("POST", "/v2/post/publish/video/init/").text()).path("post_info");
        assertEquals("PUBLIC_TO_EVERYONE", info.path("privacy_level").asText());
        assertEquals(true, info.path("disable_comment").asBoolean());   // the creator turned comments off
        assertEquals(false, info.path("disable_duet").asBoolean());
        assertEquals(true, info.path("disable_stitch").asBoolean());
        assertEquals(true, info.path("is_aigc").asBoolean());
        assertEquals("bytes 0-9/10", mock.last("PUT", "/tt-up").header("Content-Range"));

        Outcome.Published done = assertInstanceOf(Outcome.Published.class, p.poll(j));
        assertEquals("https://www.tiktok.com/@kani/video/7300000000000000001", done.url());
    }

    @Test
    void tiktokRefusesAPrivacyTheAccountNoLongerOffers() {
        creator("[\"SELF_ONLY\"]");
        TikTokPublisher p = new TikTokPublisher(new TikTokClient(http, props));
        SocialException e = assertThrows(SocialException.class, () -> p.start(job(new SocialAccount(SocialPlatform.TIKTOK, "o", "K"),
                Map.of("mode", "DIRECT", "privacy", "PUBLIC_TO_EVERYONE", "musicConsent", true), new HashMap<>())));
        assertEquals(SocialException.Kind.REJECTED, e.kind());
        assertEquals(0, mock.all("POST", "/v2/post/publish/video/init/").size());
    }

    @Test
    void tiktokInboxWaitsForTheCreator() {
        mock.on("POST /v2/post/publish/status/fetch/", r -> Reply.json(200,
                "{\"data\":{\"status\":\"SEND_TO_USER_INBOX\"},\"error\":{\"code\":\"ok\"}}"));
        TikTokPublisher p = new TikTokPublisher(new TikTokClient(http, props));
        Outcome.Wait w = assertInstanceOf(Outcome.Wait.class, p.poll(job(new SocialAccount(SocialPlatform.TIKTOK, "o", "K"),
                Map.of("mode", "INBOX"), new HashMap<>(Map.of("publishId", "pb", "uploaded", true)))));
        assertEquals(PostTarget.Status.AWAITING_USER, w.status());
        assertEquals(TikTokPublisher.INBOX_STEP, w.step());
    }

    @Test
    void privateTikTokPostHasNoLinkButSaysWhy() {
        mock.on("POST /v2/post/publish/status/fetch/", r -> Reply.json(200,
                "{\"data\":{\"status\":\"PUBLISH_COMPLETE\",\"publicaly_available_post_id\":[]},\"error\":{\"code\":\"ok\"}}"));
        TikTokPublisher p = new TikTokPublisher(new TikTokClient(http, props));
        Outcome.Published done = assertInstanceOf(Outcome.Published.class, p.poll(job(new SocialAccount(SocialPlatform.TIKTOK, "o", "K"),
                Map.of("mode", "DIRECT"), new HashMap<>(Map.of("publishId", "pb", "uploaded", true)))));
        assertNull(done.url());
        assertTrue(done.warning().contains("no public link"));
    }
}
