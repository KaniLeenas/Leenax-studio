package com.leenax.studio.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.social.SocialException.Kind;
import com.leenax.studio.social.SocialHttp.Response;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Facebook Login for Business and the Graph API: Page Reels publishing
 * (video_reels) and Instagram Reels publishing (resumable container upload).
 * Every Graph call carries an appsecret_proof.
 */
public class MetaClient {

    /** Pages: list, read, publish. Instagram: basic profile and content publishing. */
    public static final String SCOPES = String.join(",",
            "pages_show_list", "pages_read_engagement", "pages_manage_posts", "read_insights",
            "instagram_basic", "instagram_content_publish", "instagram_manage_insights", "business_management");
    private static final String NAME = "Meta";

    private final SocialHttp http;
    private final SocialProperties props;

    public MetaClient(SocialHttp http, SocialProperties props) {
        this.http = http;
        this.props = props;
    }

    private String graph() {
        return props.endpoints().graph() + "/" + props.meta().version();
    }

    // ---- sign-in -------------------------------------------------------------

    public String authorizationUrl(String state) {
        requireConfigured();
        return props.endpoints().facebookDialog() + "/" + props.meta().version() + "/dialog/oauth?" + SocialHttp.encode(SocialHttp.params(
                "client_id", props.meta().appId(),
                "redirect_uri", props.redirectUri(SocialPlatform.AuthProvider.META),
                "state", state,
                "response_type", "code",
                "scope", SCOPES));
    }

    /** Code → short-lived user token → long-lived (about 60 days) user token. */
    public TokenSet exchangeCode(String code) {
        requireConfigured();
        Response shortLived = http.send(SocialHttp.request(graph() + "/oauth/access_token?" + SocialHttp.encode(SocialHttp.params(
                "client_id", props.meta().appId(),
                "client_secret", props.meta().appSecret(),
                "redirect_uri", props.redirectUri(SocialPlatform.AuthProvider.META),
                "code", code)), Duration.ofSeconds(30)).GET().build(), NAME);
        if (!shortLived.ok()) throw error(shortLived);
        Response longLived = http.send(SocialHttp.request(graph() + "/oauth/access_token?" + SocialHttp.encode(SocialHttp.params(
                "grant_type", "fb_exchange_token",
                "client_id", props.meta().appId(),
                "client_secret", props.meta().appSecret(),
                "fb_exchange_token", shortLived.text("/access_token"))), Duration.ofSeconds(30)).GET().build(), NAME);
        if (!longLived.ok()) throw error(longLived);
        long expires = longLived.json().path("expires_in").asLong(60L * 24 * 3600);
        return new TokenSet(longLived.text("/access_token"), null, Instant.now().plusSeconds(expires), null, SCOPES);
    }

    /** A Facebook Page the user manages, with its Page token and any linked Instagram professional account. */
    public record Page(String id, String name, String pageToken, String pictureUrl,
                       String instagramId, String instagramUsername, String instagramPicture) {
        @Override
        public String toString() {
            return "Page[" + id + " " + name + "]";
        }
    }

    public List<Page> pages(String userToken) {
        List<Page> out = new ArrayList<>();
        String url = withProof(graph() + "/me/accounts?" + SocialHttp.encode(SocialHttp.params(
                "fields", "id,name,access_token,picture{url},instagram_business_account{id,username,profile_picture_url}",
                "limit", "100",
                "access_token", userToken)), userToken);
        for (int page = 0; page < 10 && url != null; page++) {
            Response r = http.send(SocialHttp.request(url, Duration.ofSeconds(30)).GET().build(), NAME);
            if (!r.ok()) throw error(r);
            for (JsonNode p : r.json().path("data")) {
                JsonNode ig = p.path("instagram_business_account");
                out.add(new Page(p.path("id").asText(), p.path("name").asText("Facebook Page"), p.path("access_token").asText(null),
                        p.at("/picture/data/url").asText(null),
                        ig.path("id").asText(null), ig.path("username").asText(null), ig.path("profile_picture_url").asText(null)));
            }
            url = r.json().at("/paging/next").asText(null);
        }
        return out;
    }

    /** Best effort removal of the app's permissions for the user. */
    public void revoke(String userToken) {
        if (userToken == null) return;
        try {
            http.send(SocialHttp.request(withProof(graph() + "/me/permissions?access_token="
                    + URLEncoder.encode(userToken, StandardCharsets.UTF_8), userToken), Duration.ofSeconds(15)).DELETE().build(), NAME);
        } catch (SocialException ignored) {
            // can also be removed in Facebook settings → Business integrations
        }
    }

    /** Reads the Page's name with its token (connection check). */
    public String pageName(String pageId, String pageToken) {
        return getJson(graph() + "/" + pageId + "?fields=name", pageToken).path("name").asText(null);
    }

    // ---- Facebook Page Reels ----------------------------------------------------

    public record UploadSession(String videoId, String uploadUrl) {
    }

    public UploadSession reelStart(String pageId, String pageToken) {
        JsonNode j = post(graph() + "/" + pageId + "/video_reels", pageToken, SocialHttp.params("upload_phase", "start"));
        String videoId = j.path("video_id").asText(null);
        if (videoId == null) throw new SocialException(Kind.TEMPORARY, "Facebook did not open an upload session");
        String uploadUrl = j.path("upload_url").asText(props.endpoints().rupload() + "/video-upload/" + props.meta().version() + "/" + videoId);
        return new UploadSession(videoId, uploadUrl);
    }

    public void reelFinish(String pageId, String pageToken, String videoId, String description, String title) {
        Map<String, String> f = SocialHttp.params("upload_phase", "finish", "video_id", videoId, "video_state", "PUBLISHED");
        if (description != null && !description.isBlank()) f.put("description", description);
        if (title != null && !title.isBlank()) f.put("title", title);
        JsonNode j = post(graph() + "/" + pageId + "/video_reels", pageToken, f);
        if (!j.path("success").asBoolean(true)) throw new SocialException(Kind.REJECTED, "Facebook did not accept the reel");
    }

    public record VideoStatus(String videoStatus, String uploadStatus, long bytesTransferred, String processingStatus,
                              String publishingStatus, String errorMessage, String permalink) {
    }

    public VideoStatus videoStatus(String videoId, String token) {
        JsonNode j = getJson(graph() + "/" + videoId + "?fields=status,permalink_url", token);
        JsonNode s = j.path("status");
        String err = s.at("/processing_phase/errors/0/message").asText(null);
        if (err == null) err = s.at("/uploading_phase/errors/0/message").asText(null);
        String link = j.path("permalink_url").asText(null);
        if (link != null && link.startsWith("/")) link = "https://www.facebook.com" + link;
        return new VideoStatus(s.path("video_status").asText(null), s.at("/uploading_phase/status").asText(null),
                s.at("/uploading_phase/bytes_transfered").asLong(s.at("/uploading_phase/bytes_transferred").asLong(0)),
                s.at("/processing_phase/status").asText(null), s.at("/publishing_phase/status").asText(null),
                SocialHttp.clip(err), link);
    }

    // ---- Instagram Reels -----------------------------------------------------------

    public String igCreateReel(String igUserId, String token, String caption, boolean shareToFeed, Long thumbOffsetMs) {
        Map<String, String> f = SocialHttp.params("media_type", "REELS", "upload_type", "resumable",
                "share_to_feed", Boolean.toString(shareToFeed));
        if (caption != null && !caption.isBlank()) f.put("caption", caption);
        if (thumbOffsetMs != null) f.put("thumb_offset", Long.toString(thumbOffsetMs));
        JsonNode j = post(graph() + "/" + igUserId + "/media", token, f);
        String id = j.path("id").asText(null);
        if (id == null) throw new SocialException(Kind.TEMPORARY, "Instagram did not create a media container");
        return id;
    }

    public String igUploadUrl(String containerId) {
        return props.endpoints().rupload() + "/ig-api-upload/" + props.meta().version() + "/" + containerId;
    }

    public record ContainerStatus(String code, String detail) {
    }

    public ContainerStatus igContainerStatus(String containerId, String token) {
        JsonNode j = getJson(graph() + "/" + containerId + "?fields=status_code,status", token);
        return new ContainerStatus(j.path("status_code").asText("IN_PROGRESS"), SocialHttp.clip(j.path("status").asText(null)));
    }

    public String igPublish(String igUserId, String token, String containerId) {
        JsonNode j = post(graph() + "/" + igUserId + "/media_publish", token, SocialHttp.params("creation_id", containerId));
        String id = j.path("id").asText(null);
        if (id == null) throw new SocialException(Kind.TEMPORARY, "Instagram did not return the published post");
        return id;
    }

    public String igPermalink(String mediaId, String token) {
        try {
            return getJson(graph() + "/" + mediaId + "?fields=permalink", token).path("permalink").asText(null);
        } catch (SocialException e) {
            return null;
        }
    }

    public record PublishingLimit(int used, int total) {
    }

    /** How many API posts the account made in the last 24 hours, and its allowance. */
    public PublishingLimit igPublishingLimit(String igUserId, String token) {
        JsonNode j = getJson(graph() + "/" + igUserId + "/content_publishing_limit?fields=config,quota_usage", token);
        JsonNode d = j.path("data").path(0);
        return new PublishingLimit(d.path("quota_usage").asInt(0), d.at("/config/quota_total").asInt(100));
    }

    // ---- performance --------------------------------------------------------------

    /**
     * A Page reel's numbers: plays from video_insights (all metrics the reel has),
     * likes and comments from the video's summaries when insights don't give them.
     */
    public Counts reelCounts(String videoId, String pageToken) {
        Counts insights = Counts.empty();
        try {
            JsonNode j = getJson(graph() + "/" + videoId + "/video_insights", pageToken);
            Map<String, Long> m = new LinkedHashMap<>();
            Map<String, Object> details = new LinkedHashMap<>();
            for (JsonNode metric : j.path("data")) {
                JsonNode value = metric.at("/values/0/value");
                String name = metric.path("name").asText();
                if (value.isNumber()) m.put(name, value.asLong());
                else if (value.isObject()) {
                    long sum = 0;
                    for (JsonNode v : value) sum += v.asLong(0);
                    m.put(name, sum);
                    details.put(name, mapper(value));
                }
            }
            Long plays = m.getOrDefault("blue_reels_play_count", m.get("fb_reels_total_plays"));
            JsonNode social = j.path("data");
            Long shares = null;
            Long comments = null;
            for (JsonNode metric : social) {
                if ("post_video_social_actions".equals(metric.path("name").asText())) {
                    JsonNode v = metric.at("/values/0/value");
                    if (v.has("SHARE")) shares = v.path("SHARE").asLong();
                    if (v.has("COMMENT")) comments = v.path("COMMENT").asLong();
                }
            }
            insights = new Counts(plays, m.get("post_video_likes_by_reaction_type"), comments, shares, null,
                    m.get("post_impressions_unique"), details);
        } catch (SocialException e) {
            if (e.kind() == Kind.RATE_LIMITED || e.kind() == Kind.TEMPORARY) throw e;
            // missing read_insights permission: fall back to the public counts below
        }
        if (insights.likes() != null && insights.comments() != null) return insights;
        JsonNode basic = getJson(graph() + "/" + videoId + "?fields=likes.summary(true).limit(0),comments.summary(true).limit(0)", pageToken);
        Counts summary = new Counts(null, longOrNull(basic.at("/likes/summary/total_count")),
                longOrNull(basic.at("/comments/summary/total_count")), null, null, null, Map.of());
        return insights.orElse(summary);
    }

    /** An Instagram reel's insights, or the like and comment counts when insights aren't allowed. */
    public Counts igCounts(String mediaId, String token) {
        try {
            JsonNode j = getJson(graph() + "/" + mediaId + "/insights?metric=views,reach,likes,comments,shares,saved", token);
            Map<String, Long> m = new LinkedHashMap<>();
            for (JsonNode metric : j.path("data")) {
                JsonNode v = metric.at("/values/0/value");
                if (v.isNumber()) m.put(metric.path("name").asText(), v.asLong());
            }
            Counts c = new Counts(m.get("views"), m.get("likes"), m.get("comments"), m.get("shares"), m.get("saved"), m.get("reach"), Map.of());
            if (!c.isEmpty()) return c;
        } catch (SocialException e) {
            if (e.kind() == Kind.RATE_LIMITED || e.kind() == Kind.TEMPORARY) throw e;
            // instagram_manage_insights not granted: use the basic fields
        }
        JsonNode basic = getJson(graph() + "/" + mediaId + "?fields=like_count,comments_count", token);
        return new Counts(null, longOrNull(basic.path("like_count")), longOrNull(basic.path("comments_count")),
                null, null, null, Map.of("source", "basic"));
    }

    private static Long longOrNull(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || !n.canConvertToLong() ? null : n.asLong();
    }

    private Map<String, Object> mapper(JsonNode value) {
        Map<String, Object> m = new LinkedHashMap<>();
        value.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asLong()));
        return m;
    }

    // ---- shared upload (rupload) ----------------------------------------------------

    /** Sends file bytes from {@code offset} to a rupload URL (Page reels and Instagram use the same protocol). */
    public void rupload(String uploadUrl, String token, Path file, long size, long offset) {
        Response r = http.send(SocialHttp.request(uploadUrl, Duration.ofHours(3))
                .header("Authorization", "OAuth " + token)
                .header("offset", Long.toString(offset))
                .header("file_size", Long.toString(size))
                .header("Content-Type", "application/octet-stream")
                .POST(SocialHttp.filePart(file, offset, size - offset)).build(), NAME);
        if (r.ok() && !r.json().path("debug_info").has("message")) return;
        JsonNode dbg = r.json().path("debug_info");
        if (!dbg.isMissingNode()) {
            String msg = "Meta upload: " + SocialHttp.clip(dbg.path("message").asText("failed"));
            throw new SocialException(dbg.path("retriable").asBoolean(false) ? Kind.TEMPORARY : Kind.REJECTED, msg);
        }
        throw error(r);
    }

    // ---- plumbing -----------------------------------------------------------------------

    private JsonNode post(String url, String token, Map<String, String> fields) {
        Map<String, String> f = new LinkedHashMap<>(fields);
        f.put("access_token", token);
        f.put("appsecret_proof", proof(token));
        Response r = http.send(SocialHttp.request(url, Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(SocialHttp.form(f)).build(), NAME);
        if (!r.ok()) throw error(r);
        return r.json();
    }

    private JsonNode getJson(String url, String token) {
        String full = url + (url.contains("?") ? "&" : "?") + "access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        Response r = http.send(SocialHttp.request(withProof(full, token), Duration.ofSeconds(30)).GET().build(), NAME);
        if (!r.ok()) throw error(r);
        return r.json();
    }

    private String withProof(String url, String token) {
        return url + (url.contains("?") ? "&" : "?") + "appsecret_proof=" + proof(token);
    }

    /** HMAC-SHA256 of the token with the app secret (Graph API "Require App Secret"). */
    String proof(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.meta().appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireConfigured() {
        if (!props.meta().configured()) {
            throw new SocialException(Kind.NOT_CONFIGURED, "Set META_APP_ID and META_APP_SECRET to connect Facebook and Instagram.");
        }
    }

    /** Maps the Graph API error envelope ({"error":{"message","code","error_subcode","is_transient"}}). */
    static SocialException error(Response r) {
        JsonNode e = r.json().path("error");
        int code = e.path("code").asInt(0);
        int sub = e.path("error_subcode").asInt(0);
        String userMsg = e.path("error_user_msg").asText(null);
        String msg = SocialHttp.clip(userMsg != null ? userMsg : e.path("message").asText("HTTP " + r.status()));
        String text = "Meta: " + msg;
        if (code == 190 || code == 102) {
            return new SocialException(Kind.RECONNECT, "Meta sign-in expired or was revoked. Reconnect Facebook and Instagram.");
        }
        if (code == 10 || (code >= 200 && code < 300)) {
            return new SocialException(Kind.RECONNECT, text + " (reconnect and allow every requested permission)");
        }
        if (code == 4 || code == 17 || code == 32 || code == 613 || (code >= 80001 && code <= 80014)
                || (code == 9 && sub == 2207042) || r.status() == 429) {
            return new SocialException(Kind.RATE_LIMITED, text, SocialHttp.retryAfter(r, Duration.ofMinutes(30)));
        }
        if (e.path("is_transient").asBoolean(false) || code == 1 || code == 2 || r.status() >= 500) {
            return new SocialException(Kind.TEMPORARY, text);
        }
        return new SocialException(Kind.REJECTED, text);
    }
}
