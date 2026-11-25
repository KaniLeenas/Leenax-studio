package com.leenax.studio.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.social.PlatformMock;
import com.leenax.studio.social.PlatformMock.Reply;
import com.leenax.studio.social.SocialAccount;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialHttp;
import com.leenax.studio.social.SocialPlatform;
import com.leenax.studio.social.SocialProperties;
import com.leenax.studio.social.YouTubeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E9 publishing: what counts as public, YouTube settings, playlists and captions after the upload. */
class YouTubeE9Test {

    private final ObjectMapper mapper = new ObjectMapper();
    private PlatformMock mock;
    private SocialProperties props;
    private SocialHttp http;
    private Path video;

    @BeforeEach
    void setUp() throws Exception {
        mock = new PlatformMock();
        http = new SocialHttp(mapper);
        props = PlatformMock.props(mock.url());
        video = Files.createTempFile("leenax-e9-", ".mp4");
        Files.write(video, "0123456789".getBytes(StandardCharsets.US_ASCII));
    }

    @AfterEach
    void tearDown() throws Exception {
        mock.close();
        Files.deleteIfExists(video);
    }

    private Job job(Map<String, Object> settings, Map<String, Object> state) {
        return new Job(new SocialAccount(SocialPlatform.YOUTUBE, "UC1", "Channel"), settings, state, 10, "video/mp4", 30_000L,
                () -> "tok", () -> video, null, (s, step, p) -> { });
    }

    private static Map<String, Object> base() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("title", "Ohm's law");
        s.put("description", "Approved description");
        s.put("tags", List.of("physics"));
        s.put("privacy", "unlisted");
        s.put("madeForKids", false);
        s.put("aiGenerated", true);
        return s;
    }

    // ---- what needs a public confirmation -------------------------------------------------------------

    @Test
    void publicMeansSeenByAnyoneNowOrLater() {
        assertTrue(Authorisations.isPublic(SocialPlatform.YOUTUBE, Map.of("privacy", "public")));
        assertFalse(Authorisations.isPublic(SocialPlatform.YOUTUBE, Map.of("privacy", "unlisted")));
        assertFalse(Authorisations.isPublic(SocialPlatform.YOUTUBE, Map.of("privacy", "private")));
        assertTrue(Authorisations.isPublic(SocialPlatform.YOUTUBE, Map.of("privacy", "private", "publishAt", "2027-01-01T00:00:00Z")),
                "a scheduled video goes public by itself");
        assertFalse(Authorisations.isPublic(SocialPlatform.TIKTOK, Map.of("privacy", "SELF_ONLY")));
        assertTrue(Authorisations.isPublic(SocialPlatform.TIKTOK, Map.of("privacy", "FOLLOWER_OF_CREATOR")));
        assertTrue(Authorisations.isPublic(SocialPlatform.INSTAGRAM, Map.of()));
    }

    @Test
    void theStatementSaysWhatWasAuthorised() {
        String s = Authorisations.statement(SocialPlatform.YOUTUBE, "Leenax", Map.of("title", "Ohm's law", "privacy", "unlisted"), false);
        assertTrue(s.contains("“Ohm's law”") && s.contains("YouTube") && s.contains("Leenax") && s.contains("unlisted"), s);
        String p = Authorisations.statement(SocialPlatform.YOUTUBE, "Leenax", Map.of("title", "T", "privacy", "private",
                "publishAt", "2027-01-01T00:00:00Z"), true);
        assertTrue(p.contains("goes public at 2027-01-01T00:00:00Z"), p);
    }

    // ---- settings ---------------------------------------------------------------------------------------

    private static Map<String, Object> normalize(Map<String, Object> s, List<String> errors) {
        return PlatformRules.normalize(SocialPlatform.YOUTUBE, s, new PlatformRules.MediaFacts(1000L, 60_000L, 1920, 1080), errors);
    }

    @Test
    void scheduledVideosAreUploadedPrivateWithAPublishTime() {
        Map<String, Object> s = base();
        s.put("privacy", "private");
        String at = Instant.now().plus(Duration.ofDays(2)).toString();
        s.put("publishAt", at);
        List<String> e = new ArrayList<>();
        Map<String, Object> out = normalize(s, e);
        assertTrue(e.isEmpty(), e.toString());
        assertEquals(Instant.parse(at).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), out.get("publishAt"));

        s.put("privacy", "unlisted");
        normalize(s, e);
        assertTrue(e.stream().anyMatch(x -> x.contains("uploaded as private")), e.toString());
        e.clear();
        s.put("privacy", "private");
        s.put("publishAt", Instant.now().plusSeconds(60).toString());
        normalize(s, e);
        assertTrue(e.stream().anyMatch(x -> x.contains("15 minutes")), e.toString());
        e.clear();
        s.put("publishAt", "next tuesday");
        normalize(s, e);
        assertTrue(e.stream().anyMatch(x -> x.contains("valid date")), e.toString());
    }

    @Test
    void playlistsAndCaptionsAreChecked() {
        Map<String, Object> s = base();
        s.put("playlistIds", List.of("PLabcdefghij123", "PLabcdefghij123", "bad id!"));
        s.put("captions", List.of(Map.of("language", "ta", "name", "தமிழ்", "srt", "1\n00:00:00,000 --> 00:00:01,000\nவணக்கம்\n")));
        List<String> e = new ArrayList<>();
        Map<String, Object> out = normalize(s, e);
        assertEquals(List.of("YouTube: a playlist id isn't valid"), e);
        assertEquals(List.of("PLabcdefghij123"), out.get("playlistIds"));
        e.clear();
        s.put("playlistIds", List.of("PLabcdefghij123"));
        s.put("captions", List.of(Map.of("language", "", "name", "x", "srt", "")));
        normalize(s, e);
        assertTrue(e.contains("YouTube: a caption file needs its language (like en or ta)") && e.contains("YouTube: a caption file is empty or too long"), e.toString());
    }

    @Test
    void thePublishTimeGoesToYouTube() {
        Map<String, Object> s = base();
        s.put("privacy", "private");
        s.put("publishAt", "2027-03-01T09:00:00Z");
        Map<String, Object> meta = YouTubePublisher.metadata(job(s, new HashMap<>()));
        @SuppressWarnings("unchecked") Map<String, Object> status = (Map<String, Object>) meta.get("status");
        assertEquals("private", status.get("privacyStatus"));
        assertEquals("2027-03-01T09:00:00Z", status.get("publishAt"));
        Map<String, Object> plain = YouTubePublisher.metadata(job(base(), new HashMap<>()));
        @SuppressWarnings("unchecked") Map<String, Object> st2 = (Map<String, Object>) plain.get("status");
        assertNull(st2.get("publishAt"));
    }

    // ---- after the upload: playlists and captions ------------------------------------------------------------

    private void uploadRoutes() {
        String session = mock.url() + "/session/9";
        mock.on("POST /upload/youtube/v3/videos", r -> Reply.json(200, "").with("Location", session));
        mock.on("PUT /session/9", r -> Reply.json(200, "{\"id\":\"vid9\"}"));
    }

    @Test
    void addsToPlaylistsAndUploadsCaptionsOnce() throws Exception {
        uploadRoutes();
        mock.on("POST /youtube/v3/playlistItems", r -> Reply.json(200, "{\"id\":\"pi1\"}"));
        mock.on("POST /upload/youtube/v3/captions", r -> Reply.json(200, "{\"id\":\"cap1\"}"));
        Map<String, Object> s = base();
        s.put("playlistIds", List.of("PLaaaaaaaaaa1", "PLbbbbbbbbbb2"));
        String srt = "1\n00:00:00,000 --> 00:00:02,000\nவணக்கம்\n\n";
        s.put("captions", List.of(Map.of("language", "ta", "name", "தமிழ்", "srt", srt)));
        YouTubePublisher p = new YouTubePublisher(new YouTubeClient(http, props));
        Map<String, Object> state = new HashMap<>();
        Job j = job(s, state);
        assertInstanceOf(Outcome.Wait.class, p.start(j));
        assertEquals(2, mock.all("POST", "/youtube/v3/playlistItems").size());
        JsonNode item = mapper.readTree(mock.all("POST", "/youtube/v3/playlistItems").get(1).text());
        assertEquals("PLbbbbbbbbbb2", item.at("/snippet/playlistId").asText());
        assertEquals("vid9", item.at("/snippet/resourceId/videoId").asText());
        PlatformMock.Req cap = mock.last("POST", "/upload/youtube/v3/captions");
        assertTrue(cap.query().contains("uploadType=multipart"), cap.query());
        assertTrue(cap.header("Content-Type").startsWith("multipart/related; boundary="), cap.header("Content-Type"));
        String body = new String(cap.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"videoId\":\"vid9\"") && body.contains("\"language\":\"ta\"") && body.contains("வணக்கம்"), body);
        assertEquals("Bearer tok", cap.header("Authorization"));

        // a restart doesn't repeat them
        p.start(j);
        assertEquals(2, mock.all("POST", "/youtube/v3/playlistItems").size());
        assertEquals(1, mock.all("POST", "/upload/youtube/v3/captions").size());
        assertEquals(1, mock.all("POST", "/upload/youtube/v3/videos").size());
    }

    @Test
    void aRefusedPlaylistIsAWarningButAQuotaErrorRetriesLater() {
        uploadRoutes();
        AtomicInteger capCalls = new AtomicInteger();
        mock.on("POST /youtube/v3/playlistItems", r -> Reply.json(403,
                "{\"error\":{\"message\":\"Insufficient Permission\",\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}"));
        mock.on("POST /upload/youtube/v3/captions", r -> capCalls.incrementAndGet() == 1
                ? Reply.json(403, "{\"error\":{\"message\":\"quota\",\"errors\":[{\"reason\":\"quotaExceeded\"}]}}")
                : Reply.json(200, "{\"id\":\"cap1\"}"));
        Map<String, Object> s = base();
        s.put("playlistIds", List.of("PLaaaaaaaaaa1"));
        s.put("captions", List.of(Map.of("language", "en", "name", "English", "srt", "1\n00:00:00,000 --> 00:00:01,000\nHi\n\n")));
        YouTubePublisher p = new YouTubePublisher(new YouTubeClient(http, props));
        Map<String, Object> state = new HashMap<>();
        Job j = job(s, state);
        SocialException e = assertThrows(SocialException.class, () -> p.start(j));
        assertEquals(SocialException.Kind.RATE_LIMITED, e.kind());
        assertEquals("vid9", state.get("videoId"), "the uploaded video is kept");
        assertTrue(String.valueOf(state.get("extrasWarning")).contains("Not added to a playlist"), String.valueOf(state.get("extrasWarning")));
        assertInstanceOf(Outcome.Wait.class, p.start(j));
        assertEquals(1, mock.all("POST", "/upload/youtube/v3/videos").size(), "no second upload");
        assertEquals(1, mock.all("POST", "/youtube/v3/playlistItems").size(), "the refused playlist isn't asked again");
        assertEquals(2, capCalls.get());
    }

    @Test
    void scopesAreCheckedByName() {
        String old = "https://www.googleapis.com/auth/youtube.upload https://www.googleapis.com/auth/youtube.readonly";
        assertFalse(YouTubeClient.granted(old, YouTubeClient.SCOPE_MANAGE));
        assertTrue(YouTubeClient.granted(YouTubeClient.SCOPES, YouTubeClient.SCOPE_MANAGE, YouTubeClient.SCOPE_ANALYTICS));
        assertTrue(YouTubeClient.granted(old.replace(' ', ',') + "," + YouTubeClient.SCOPE_ANALYTICS, YouTubeClient.SCOPE_ANALYTICS));
        assertFalse(YouTubeClient.granted(null, YouTubeClient.SCOPE_ANALYTICS));
    }

    @Test
    void playlistsAreListedAndCreated() throws Exception {
        mock.on("GET /youtube/v3/playlists", r -> r.query().contains("pageToken=p2")
                ? Reply.json(200, "{\"items\":[{\"id\":\"PL2\",\"snippet\":{\"title\":\"Waves\"},\"status\":{\"privacyStatus\":\"public\"},\"contentDetails\":{\"itemCount\":3}}]}")
                : Reply.json(200, "{\"nextPageToken\":\"p2\",\"items\":[{\"id\":\"PL1\",\"snippet\":{\"title\":\"Circuits\"},\"status\":{\"privacyStatus\":\"unlisted\"},\"contentDetails\":{\"itemCount\":7}}]}"));
        mock.on("POST /youtube/v3/playlists", r -> Reply.json(200, "{\"id\":\"PLnew\"}"));
        YouTubeClient yt = new YouTubeClient(http, props);
        List<YouTubeClient.Playlist> lists = yt.playlists("tok");
        assertEquals(2, lists.size());
        assertEquals("Circuits", lists.get(0).title());
        assertEquals(7L, lists.get(0).items());
        YouTubeClient.Playlist made = yt.createPlaylist("tok", "Optics", "Lessons", "public");
        assertEquals("PLnew", made.id());
        JsonNode sent = mapper.readTree(mock.last("POST", "/youtube/v3/playlists").text());
        assertEquals("Optics", sent.at("/snippet/title").asText());
        assertEquals("public", sent.at("/status/privacyStatus").asText());
    }

    @Test
    void channelDetailsRespectHiddenSubscriberCounts() {
        mock.on("GET /youtube/v3/channels", r -> Reply.json(200,
                "{\"items\":[{\"id\":\"UC1\",\"snippet\":{\"title\":\"Leenax\",\"customUrl\":\"@leenax\"},"
                        + "\"statistics\":{\"hiddenSubscriberCount\":true,\"subscriberCount\":\"0\",\"videoCount\":\"12\",\"viewCount\":\"3400\"}}]}"));
        YouTubeClient.ChannelInfo c = new YouTubeClient(http, props).channelInfo("tok");
        assertEquals("Leenax", c.title());
        assertTrue(c.subscribersHidden());
        assertNull(c.subscribers());
        assertEquals(12L, c.videos());
        assertEquals(3400L, c.views());
    }
}
