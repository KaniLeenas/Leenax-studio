package com.leenax.studio.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.crypto.SecretCipher;
import com.leenax.studio.social.PlatformMock.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Request formats and error handling of the YouTube, Meta and TikTok clients, against a local mock. */
class SocialClientsTest {

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

    private static Path file(long size) throws Exception {
        Path p = Files.createTempFile("leenax-test-", ".mp4");
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(size);
            f.seek(0);
            f.write("0123456789".getBytes(StandardCharsets.US_ASCII), 0, (int) Math.min(10, size));
        }
        return p;
    }

    // ---- shared -------------------------------------------------------------------

    @Test
    void pkceChallengeIsBase64UrlSha256() {
        // Expected value computed independently with Python hashlib + urlsafe_b64encode.
        assertEquals("_AWBeFYfqCv0TczQF3GjFnIOFPeSSAkUC7i9M4qdGdY", Pkce.challenge("dBjftJeZ4CVP-mA0g4ry8ZCIZWbdSxGqd9KaKLqXJ5w"));
        assertEquals(43, Pkce.randomToken().length());
        assertFalse(Pkce.randomToken().equals(Pkce.randomToken()));
        assertEquals(64, Pkce.sha256Hex("x").length());
    }

    @Test
    void redirectUrisFollowThePublicAddress() {
        SocialProperties p = new SocialProperties("https://studio.example.com/", null, null, null, null);
        assertEquals("https://studio.example.com/api/v1/social/oauth/google/callback", p.redirectUri(SocialPlatform.AuthProvider.GOOGLE));
        assertFalse(p.configured(SocialPlatform.AuthProvider.META));
        assertEquals("v26.0", p.meta().version());
        assertEquals("https://graph.facebook.com", p.endpoints().graph());
    }

    @Test
    void tokenCiphertextIsBoundToAccountAndKind() {
        SecretCipher cipher = new SecretCipher(new byte[32]);
        java.util.UUID a = java.util.UUID.randomUUID();
        byte[] blob = cipher.encrypt("ya29.token", TokenVault.aad(a, "access"));
        assertEquals("ya29.token", cipher.decrypt(blob, TokenVault.aad(a, "access")));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(blob, TokenVault.aad(a, "refresh")));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(blob, TokenVault.aad(java.util.UUID.randomUUID(), "access")));
        assertFalse(new TokenSet("secret-access", "secret-refresh", null, null, null).toString().contains("secret"));
    }

    // ---- YouTube ----------------------------------------------------------------------

    @Test
    void googleSignInUsesPkceAndOfflineAccess() {
        YouTubeClient yt = new YouTubeClient(http, props);
        String url = yt.authorizationUrl("st4te", "chall");
        Map<String, String> q = PlatformMock.Req.decode(url.substring(url.indexOf('?') + 1));
        assertTrue(url.startsWith(mock.url() + "/google/auth?"));
        assertEquals("g-client", q.get("client_id"));
        assertEquals("https://studio.test/api/v1/social/oauth/google/callback", q.get("redirect_uri"));
        assertEquals("offline", q.get("access_type"));
        assertEquals("consent", q.get("prompt"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals("chall", q.get("code_challenge"));
        assertEquals("st4te", q.get("state"));
        assertTrue(q.get("scope").contains("youtube.upload"));

        mock.on("POST /token", r -> Reply.json(200, "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3599,\"scope\":\"s\"}"));
        TokenSet t = yt.exchangeCode("the-code", "the-verifier");
        assertEquals("at", t.accessToken());
        assertEquals("rt", t.refreshToken());
        Map<String, String> form = mock.last("POST", "/token").form();
        assertEquals("authorization_code", form.get("grant_type"));
        assertEquals("the-verifier", form.get("code_verifier"));
        assertEquals("g-secret", form.get("client_secret"));
    }

    @Test
    void revokedGoogleRefreshTokenMeansReconnect() {
        mock.on("POST /token", r -> Reply.json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}"));
        SocialException e = assertThrows(SocialException.class, () -> new YouTubeClient(http, props).refresh("rt"));
        assertEquals(SocialException.Kind.RECONNECT, e.kind());
    }

    @Test
    void youtubeQuotaAndPermissionErrorsAreClassified() {
        mock.on("GET /youtube/v3/channels", r -> Reply.json(403,
                "{\"error\":{\"code\":403,\"message\":\"quota\",\"errors\":[{\"reason\":\"quotaExceeded\"}]}}"));
        SocialException e = assertThrows(SocialException.class, () -> new YouTubeClient(http, props).channel("at"));
        assertEquals(SocialException.Kind.RATE_LIMITED, e.kind());
        assertTrue(e.retryAfter().toHours() >= 1);

        mock.on("GET /youtube/v3/channels", r -> Reply.json(403,
                "{\"error\":{\"code\":403,\"message\":\"no\",\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}"));
        assertEquals(SocialException.Kind.RECONNECT,
                assertThrows(SocialException.class, () -> new YouTubeClient(http, props).channel("at")).kind());

        mock.on("GET /youtube/v3/channels", r -> Reply.json(200, "{\"items\":[]}"));
        assertEquals(SocialException.Kind.REJECTED,
                assertThrows(SocialException.class, () -> new YouTubeClient(http, props).channel("at")).kind());
    }

    @Test
    void youtubeResumableUploadResumesFromTheRangeYouTubeReports() throws Exception {
        Path f = file(10);
        String session = mock.url() + "/session/abc";
        mock.on("POST /upload/youtube/v3/videos", r -> Reply.json(200, "{}").with("Location", session));
        AtomicInteger puts = new AtomicInteger();
        mock.on("PUT /session/abc", r -> {
            int n = puts.incrementAndGet();
            if (n == 1) return Reply.json(503, "");                                           // upload dropped
            if ("bytes */10".equals(r.header("Content-Range"))) return Reply.json(308, "").with("Range", "bytes=0-4");
            return Reply.json(200, "{\"id\":\"vid123\",\"status\":{\"uploadStatus\":\"uploaded\"}}");
        });
        YouTubeClient yt = new YouTubeClient(http, props);
        String url = yt.startUpload("at", Map.of("snippet", Map.of("title", "T")), 10, "video/mp4");
        assertEquals(session, url);
        PlatformMock.Req start = mock.last("POST", "/upload/youtube/v3/videos");
        assertEquals("resumable", start.params().get("uploadType"));
        assertEquals("10", start.header("X-Upload-Content-Length"));
        assertEquals("Bearer at", start.header("Authorization"));

        assertEquals("vid123", yt.upload(url, f, 10, "video/mp4", "at", false));
        List<PlatformMock.Req> all = mock.all("PUT", "/session/abc");
        assertEquals(3, all.size());
        assertEquals(10, all.get(0).body().length);
        assertEquals("bytes 5-9/10", all.get(2).header("Content-Range"));
        assertEquals("56789", all.get(2).text());
        Files.delete(f);
    }

    // ---- Meta ------------------------------------------------------------------------------

    @Test
    void metaSignInExchangesForALongLivedTokenAndListsPagesWithInstagram() throws Exception {
        MetaClient meta = new MetaClient(http, props);
        String url = meta.authorizationUrl("s1");
        assertTrue(url.startsWith(mock.url() + "/fb/v26.0/dialog/oauth?"));
        assertTrue(url.contains("instagram_content_publish"));

        mock.on("GET /v26.0/oauth/access_token", r -> r.params().containsKey("code")
                ? Reply.json(200, "{\"access_token\":\"short\"}")
                : Reply.json(200, "{\"access_token\":\"long\",\"expires_in\":5184000}"));
        TokenSet t = meta.exchangeCode("c");
        assertEquals("long", t.accessToken());
        assertEquals("short", mock.last("GET", "/v26.0/oauth/access_token").params().get("fb_exchange_token"));

        mock.on("GET /v26.0/me/accounts", r -> Reply.json(200, """
                {"data":[{"id":"p1","name":"My Page","access_token":"pt1","picture":{"data":{"url":"http://pic"}},
                          "instagram_business_account":{"id":"ig1","username":"me_ig"}},
                         {"id":"p2","name":"Other","access_token":"pt2"}]}"""));
        List<MetaClient.Page> pages = meta.pages("long");
        assertEquals(2, pages.size());
        assertEquals("ig1", pages.get(0).instagramId());
        assertEquals("me_ig", pages.get(0).instagramUsername());
        assertNull(pages.get(1).instagramId());
        assertFalse(pages.get(0).toString().contains("pt1"));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("m-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String proof = HexFormat.of().formatHex(mac.doFinal("long".getBytes(StandardCharsets.UTF_8)));
        assertEquals(proof, mock.last("GET", "/v26.0/me/accounts").params().get("appsecret_proof"));
    }

    @Test
    void pageReelUploadSendsOffsetAndFileSize() throws Exception {
        Path f = file(10);
        MetaClient meta = new MetaClient(http, props);
        mock.on("POST /v26.0/p1/video_reels", r -> "start".equals(r.form().get("upload_phase"))
                ? Reply.json(200, "{\"video_id\":\"v9\",\"upload_url\":\"" + mock.url() + "/rupload/video-upload/v26.0/v9\"}")
                : Reply.json(200, "{\"success\":true}"));
        mock.on("POST /rupload/video-upload/v26.0/v9", r -> Reply.json(200, "{\"success\":true}"));
        MetaClient.UploadSession s = meta.reelStart("p1", "pt");
        meta.rupload(s.uploadUrl(), "pt", f, 10, 3);
        PlatformMock.Req up = mock.last("POST", "/rupload/video-upload/v26.0/v9");
        assertEquals("OAuth pt", up.header("Authorization"));
        assertEquals("3", up.header("offset"));
        assertEquals("10", up.header("file_size"));
        assertEquals("3456789", up.text());
        meta.reelFinish("p1", "pt", "v9", "Hello #reel", "");
        Map<String, String> fin = mock.last("POST", "/v26.0/p1/video_reels").form();
        assertEquals("finish", fin.get("upload_phase"));
        assertEquals("PUBLISHED", fin.get("video_state"));
        assertEquals("Hello #reel", fin.get("description"));
        assertFalse(fin.containsKey("title"));
        assertTrue(fin.containsKey("appsecret_proof"));
        Files.delete(f);
    }

    @Test
    void metaErrorsAreClassified() {
        MetaClient meta = new MetaClient(http, props);
        mock.on("GET /v26.0/ig1/content_publishing_limit", r -> Reply.json(400,
                "{\"error\":{\"message\":\"Error validating access token\",\"code\":190}}"));
        assertEquals(SocialException.Kind.RECONNECT,
                assertThrows(SocialException.class, () -> meta.igPublishingLimit("ig1", "t")).kind());
        mock.on("GET /v26.0/ig1/content_publishing_limit", r -> Reply.json(400,
                "{\"error\":{\"message\":\"limit\",\"code\":9,\"error_subcode\":2207042}}"));
        assertEquals(SocialException.Kind.RATE_LIMITED,
                assertThrows(SocialException.class, () -> meta.igPublishingLimit("ig1", "t")).kind());
        mock.on("GET /v26.0/ig1/content_publishing_limit", r -> Reply.json(500,
                "{\"error\":{\"message\":\"oops\",\"code\":2,\"is_transient\":true}}"));
        assertEquals(SocialException.Kind.TEMPORARY,
                assertThrows(SocialException.class, () -> meta.igPublishingLimit("ig1", "t")).kind());
        mock.on("POST /rupload/ig-api-upload/v26.0/c1", r -> Reply.json(400,
                "{\"debug_info\":{\"retriable\":false,\"type\":\"ProcessingFailedError\",\"message\":\"Bad file\"}}"));
        SocialException e = assertThrows(SocialException.class, () -> meta.rupload(meta.igUploadUrl("c1"), "t", Path.of("/dev/null"), 0, 0));
        assertEquals(SocialException.Kind.REJECTED, e.kind());
        assertTrue(e.getMessage().contains("Bad file"));
    }

    // ---- TikTok ----------------------------------------------------------------------------------

    @Test
    void tiktokChunksFollowTheMediaTransferRules() {
        assertEquals(new TikTokClient.Chunks(3_000_000, 1), TikTokClient.chunks(3_000_000));
        assertEquals(new TikTokClient.Chunks(10L * 1024 * 1024, 1), TikTokClient.chunks(10L * 1024 * 1024));
        TikTokClient.Chunks c = TikTokClient.chunks(50_000_123);
        assertEquals(10L * 1024 * 1024, c.chunkSize());
        assertEquals(4, c.count());
        long last = 50_000_123 - (c.count() - 1) * c.chunkSize();
        assertTrue(last >= 5L * 1024 * 1024 && last <= 128L * 1024 * 1024);
        TikTokClient.Chunks big = TikTokClient.chunks(2L * 1024 * 1024 * 1024);
        assertTrue(big.count() <= 1000);
    }

    @Test
    void tiktokDirectPostUploadsChunksInOrder() throws Exception {
        long size = 21L * 1024 * 1024 + 7;
        Path f = file(size);
        TikTokClient tt = new TikTokClient(http, props);
        mock.on("POST /v2/post/publish/video/init/", r -> Reply.json(200,
                "{\"data\":{\"publish_id\":\"pub1\",\"upload_url\":\"" + mock.url() + "/tt-upload?sig=1\"},\"error\":{\"code\":\"ok\"}}"));
        AtomicInteger n = new AtomicInteger();
        mock.on("PUT /tt-upload", r -> Reply.json(n.incrementAndGet() == 2 ? 201 : 206, ""));
        TikTokClient.Init init = tt.init("at", Map.of("privacy_level", "SELF_ONLY", "title", "hi"), size);
        assertEquals("pub1", init.publishId());
        JsonNode body = mapper.readTree(mock.last("POST", "/v2/post/publish/video/init/").text());
        assertEquals("FILE_UPLOAD", body.at("/source_info/source").asText());
        assertEquals(size, body.at("/source_info/video_size").asLong());
        assertEquals(2, body.at("/source_info/total_chunk_count").asInt());
        assertEquals("SELF_ONLY", body.at("/post_info/privacy_level").asText());

        tt.upload(init.uploadUrl(), f, size, "video/mp4");
        List<PlatformMock.Req> puts = mock.all("PUT", "/tt-upload");
        assertEquals(2, puts.size());
        assertEquals("bytes 0-10485759/" + size, puts.get(0).header("Content-Range"));
        assertEquals("bytes 10485760-" + (size - 1) + "/" + size, puts.get(1).header("Content-Range"));
        assertEquals(size - 10485760, puts.get(1).body().length);
        assertEquals("sig=1", puts.get(0).query());
        Files.delete(f);
    }

    @Test
    void tiktokInboxModeUsesTheInboxEndpointWithoutPostInfo() throws Exception {
        mock.on("POST /v2/post/publish/inbox/video/init/", r -> Reply.json(200,
                "{\"data\":{\"publish_id\":\"pub2\",\"upload_url\":\"u\"},\"error\":{\"code\":\"ok\"}}"));
        new TikTokClient(http, props).init("at", null, 1000);
        JsonNode body = mapper.readTree(mock.last("POST", "/v2/post/publish/inbox/video/init/").text());
        assertTrue(body.path("post_info").isMissingNode());
    }

    @Test
    void tiktokCreatorInfoAndErrors() {
        TikTokClient tt = new TikTokClient(http, props);
        mock.on("POST /v2/post/publish/creator_info/query/", r -> Reply.json(200, """
                {"data":{"creator_username":"kani","creator_nickname":"Kani","privacy_level_options":["PUBLIC_TO_EVERYONE","SELF_ONLY"],
                 "comment_disabled":false,"duet_disabled":true,"stitch_disabled":true,"max_video_post_duration_sec":600},
                 "error":{"code":"ok","message":""}}"""));
        TikTokClient.CreatorInfo c = tt.creatorInfo("at");
        assertEquals("kani", c.username());
        assertEquals(List.of("PUBLIC_TO_EVERYONE", "SELF_ONLY"), c.privacyOptions());
        assertTrue(c.duetDisabled());
        assertEquals(600, c.maxVideoSeconds());

        mock.on("POST /v2/post/publish/video/init/", r -> Reply.json(403,
                "{\"error\":{\"code\":\"unaudited_client_can_only_post_to_private_accounts\",\"message\":\"x\"}}"));
        SocialException e = assertThrows(SocialException.class, () -> tt.init("at", Map.of(), 100));
        assertEquals(SocialException.Kind.REJECTED, e.kind());
        assertTrue(e.getMessage().contains("private account"));
        mock.on("POST /v2/post/publish/video/init/", r -> Reply.json(403,
                "{\"error\":{\"code\":\"spam_risk_too_many_posts\",\"message\":\"x\"}}"));
        assertEquals(SocialException.Kind.RATE_LIMITED, assertThrows(SocialException.class, () -> tt.init("at", Map.of(), 100)).kind());
        mock.on("POST /v2/post/publish/video/init/", r -> Reply.json(401,
                "{\"error\":{\"code\":\"access_token_invalid\",\"message\":\"x\"}}"));
        assertEquals(SocialException.Kind.RECONNECT, assertThrows(SocialException.class, () -> tt.init("at", Map.of(), 100)).kind());
    }

    @Test
    void tiktokTokenExchangeAndRevokedRefresh() {
        TikTokClient tt = new TikTokClient(http, props);
        mock.on("POST /v2/oauth/token/", r -> "authorization_code".equals(r.form().get("grant_type"))
                ? Reply.json(200, "{\"access_token\":\"act\",\"expires_in\":86400,\"open_id\":\"o1\",\"refresh_token\":\"rft\",\"refresh_expires_in\":31536000,\"scope\":\"video.publish\"}")
                : Reply.json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token is invalid or expired.\"}"));
        TokenSet t = tt.exchangeCode("code1");
        assertEquals("act", t.accessToken());
        assertTrue(t.refreshExpiresAt().isAfter(t.accessExpiresAt()));
        assertEquals("t-key", mock.last("POST", "/v2/oauth/token/").form().get("client_key"));
        assertEquals(SocialException.Kind.RECONNECT, assertThrows(SocialException.class, () -> tt.refresh("rft")).kind());
    }
}
