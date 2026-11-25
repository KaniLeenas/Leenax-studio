package com.leenax.studio.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.social.SocialException.Kind;
import com.leenax.studio.social.SocialHttp.Response;

import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TikTok Login Kit (OAuth v2) and the Content Posting API: creator info,
 * Direct Post or upload-to-inbox with FILE_UPLOAD chunks, and post status.
 */
public class TikTokClient {

    public static final String SCOPES = "user.info.basic,video.publish,video.upload,video.list";
    private static final String NAME = "TikTok";

    /** TikTok chunk rules: 5–64 MB per chunk, the last may hold up to 128 MB, at most 1000 chunks. */
    static final long MIN_CHUNK = 5L * 1024 * 1024;
    static final long CHUNK = 10L * 1024 * 1024;

    private final SocialHttp http;
    private final SocialProperties props;

    public TikTokClient(SocialHttp http, SocialProperties props) {
        this.http = http;
        this.props = props;
    }

    private String api() {
        return props.endpoints().tiktokApi();
    }

    // ---- sign-in -----------------------------------------------------------------

    public String authorizationUrl(String state) {
        requireConfigured();
        return props.endpoints().tiktokAuth() + "?" + SocialHttp.encode(SocialHttp.params(
                "client_key", props.tiktok().clientKey(),
                "scope", SCOPES,
                "response_type", "code",
                "redirect_uri", props.redirectUri(SocialPlatform.AuthProvider.TIKTOK),
                "state", state));
    }

    public TokenSet exchangeCode(String code) {
        requireConfigured();
        return tokenCall(SocialHttp.params(
                "client_key", props.tiktok().clientKey(),
                "client_secret", props.tiktok().clientSecret(),
                "code", code,
                "grant_type", "authorization_code",
                "redirect_uri", props.redirectUri(SocialPlatform.AuthProvider.TIKTOK)));
    }

    public TokenSet refresh(String refreshToken) {
        requireConfigured();
        if (refreshToken == null) throw new SocialException(Kind.RECONNECT, "TikTok sign-in expired. Reconnect TikTok.");
        return tokenCall(SocialHttp.params(
                "client_key", props.tiktok().clientKey(),
                "client_secret", props.tiktok().clientSecret(),
                "grant_type", "refresh_token",
                "refresh_token", refreshToken));
    }

    private TokenSet tokenCall(Map<String, String> form) {
        Response r = http.send(SocialHttp.request(api() + "/v2/oauth/token/", Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(SocialHttp.form(form)).build(), NAME);
        String error = r.text("/error");
        if (!r.ok() || (error != null && !error.isBlank() && !"ok".equals(error))) {
            if ("invalid_grant".equals(error)) {
                throw new SocialException(Kind.RECONNECT, "TikTok sign-in expired or was revoked. Reconnect TikTok.");
            }
            if ("invalid_client".equals(error)) {
                throw new SocialException(Kind.NOT_CONFIGURED, "TikTok rejected the client key or secret.");
            }
            if (r.status() >= 500 || r.status() == 429) throw new SocialException(Kind.TEMPORARY, "TikTok sign-in is unavailable right now");
            throw new SocialException(Kind.REJECTED, "TikTok sign-in failed: " + SocialHttp.clip(r.text("/error_description")));
        }
        Instant now = Instant.now();
        return new TokenSet(r.text("/access_token"), r.text("/refresh_token"),
                now.plusSeconds(r.json().path("expires_in").asLong(86_400)),
                now.plusSeconds(r.json().path("refresh_expires_in").asLong(365L * 86_400)),
                r.text("/scope"));
    }

    public void revoke(String accessToken) {
        if (accessToken == null || !props.tiktok().configured()) return;
        try {
            http.send(SocialHttp.request(api() + "/v2/oauth/revoke/", Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(SocialHttp.form(SocialHttp.params(
                            "client_key", props.tiktok().clientKey(),
                            "client_secret", props.tiktok().clientSecret(),
                            "token", accessToken))).build(), NAME);
        } catch (SocialException ignored) {
            // the app can also be removed in TikTok → Settings → Security → Manage app permissions
        }
    }

    public Profile user(String accessToken, String openId) {
        Response r = http.send(SocialHttp.request(api() + "/v2/user/info/?fields=open_id,avatar_url,display_name", Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken).GET().build(), NAME);
        JsonNode u = check(r).path("data").path("user");
        String id = u.path("open_id").asText(openId);
        return new Profile(SocialPlatform.TIKTOK, id, u.path("display_name").asText("TikTok account"), null,
                u.path("avatar_url").asText(null));
    }

    // ---- posting -------------------------------------------------------------------------

    /** What the creator may post right now. TikTok requires showing this before every post. */
    public record CreatorInfo(String username, String nickname, String avatarUrl, List<String> privacyOptions,
                              boolean commentDisabled, boolean duetDisabled, boolean stitchDisabled,
                              int maxVideoSeconds) {
    }

    public CreatorInfo creatorInfo(String accessToken) {
        Response r = http.send(SocialHttp.request(api() + "/v2/post/publish/creator_info/query/", Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), NAME);
        JsonNode d = check(r).path("data");
        List<String> privacy = new ArrayList<>();
        d.path("privacy_level_options").forEach(n -> privacy.add(n.asText()));
        return new CreatorInfo(d.path("creator_username").asText(null), d.path("creator_nickname").asText(null),
                d.path("creator_avatar_url").asText(null), privacy,
                d.path("comment_disabled").asBoolean(false), d.path("duet_disabled").asBoolean(false),
                d.path("stitch_disabled").asBoolean(false), d.path("max_video_post_duration_sec").asInt(0));
    }

    public record Chunks(long chunkSize, int count) {
    }

    /** Splits a file the way TikTok expects: fixed chunks, trailing bytes merged into the last one. */
    public static Chunks chunks(long size) {
        if (size <= 0) throw new IllegalArgumentException("Empty file");
        if (size <= CHUNK) return new Chunks(size, 1);   // under 5 MB must be one chunk; up to 10 MB is fine as one
        long count = size / CHUNK;
        return new Chunks(CHUNK, (int) count);
    }

    public record Init(String publishId, String uploadUrl) {
    }

    /**
     * Starts a post. {@code postInfo} null means "upload to inbox" (the creator
     * finishes it in the TikTok app); otherwise it is a Direct Post.
     */
    public Init init(String accessToken, Map<String, Object> postInfo, long size) {
        Chunks c = chunks(size);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source", "FILE_UPLOAD");
        source.put("video_size", size);
        source.put("chunk_size", c.chunkSize());
        source.put("total_chunk_count", c.count());
        Map<String, Object> body = new LinkedHashMap<>();
        if (postInfo != null) body.put("post_info", postInfo);
        body.put("source_info", source);
        String path = postInfo != null ? "/v2/post/publish/video/init/" : "/v2/post/publish/inbox/video/init/";
        Response r = http.send(SocialHttp.request(api() + path, Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(http.json(body)).build(), NAME);
        JsonNode d = check(r).path("data");
        return new Init(d.path("publish_id").asText(), d.path("upload_url").asText(null));
    }

    /** PUTs every chunk in order to the upload URL (valid for one hour). */
    public void upload(String uploadUrl, Path file, long size, String contentType) {
        Chunks c = chunks(size);
        for (int i = 0; i < c.count(); i++) {
            long first = i * c.chunkSize();
            long last = i == c.count() - 1 ? size - 1 : first + c.chunkSize() - 1;
            long length = last - first + 1;
            Response r = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    r = http.send(SocialHttp.request(uploadUrl, Duration.ofMinutes(30))
                            .header("Content-Type", contentType)
                            .header("Content-Range", "bytes " + first + "-" + last + "/" + size)
                            .PUT(SocialHttp.filePart(file, first, length)).build(), NAME);
                } catch (SocialException e) {
                    if (attempt == 2) throw e;
                    continue;
                }
                if (r.status() < 500) break;
            }
            int s = r.status();
            if (s == 201 || s == 206 || s == 200) continue;
            if (s == 403) throw new SocialException(Kind.TEMPORARY, "TikTok upload link expired; starting again");
            if (s >= 500) throw new SocialException(Kind.TEMPORARY, "TikTok upload failed (HTTP " + s + ")");
            throw new SocialException(Kind.REJECTED, "TikTok refused part of the upload (HTTP " + s + ")");
        }
    }

    public record Status(String status, String failReason, List<String> postIds) {
    }

    public Status status(String accessToken, String publishId) {
        Response r = http.send(SocialHttp.request(api() + "/v2/post/publish/status/fetch/", Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(http.json(Map.of("publish_id", publishId))).build(), NAME);
        JsonNode d = check(r).path("data");
        List<String> ids = new ArrayList<>();
        d.path("publicaly_available_post_id").forEach(n -> ids.add(n.asText()));
        return new Status(d.path("status").asText("PROCESSING_UPLOAD"), d.path("fail_reason").asText(null), ids);
    }

    /** Display API video query (scope video.list): counts for up to 20 of the user's own videos per call. */
    public Map<String, Counts> videoCounts(String accessToken, List<String> videoIds) {
        Map<String, Counts> out = new LinkedHashMap<>();
        for (int i = 0; i < videoIds.size(); i += 20) {
            Response r = http.send(SocialHttp.request(api() + "/v2/video/query/?fields=id,view_count,like_count,comment_count,share_count",
                            Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(http.json(Map.of("filters", Map.of("video_ids", videoIds.subList(i, Math.min(videoIds.size(), i + 20)))))).build(), NAME);
            for (JsonNode v : check(r).at("/data/videos")) {
                out.put(v.path("id").asText(), new Counts(opt(v, "view_count"), opt(v, "like_count"), opt(v, "comment_count"),
                        opt(v, "share_count"), null, null, Map.of()));
            }
        }
        return out;
    }

    private static Long opt(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asLong();
    }

    private void requireConfigured() {
        if (!props.tiktok().configured()) {
            throw new SocialException(Kind.NOT_CONFIGURED, "Set TIKTOK_CLIENT_KEY and TIKTOK_CLIENT_SECRET to connect TikTok.");
        }
    }

    /** TikTok wraps answers as {"data":…, "error":{"code":"ok"|…, "message"}}. */
    static JsonNode check(Response r) {
        String code = r.text("/error/code");
        if (r.ok() && (code == null || "ok".equals(code))) return r.json();
        throw error(r.status(), code, r.text("/error/message"), r);
    }

    static SocialException error(int status, String code, String message, Response r) {
        String text = "TikTok: " + (message == null || message.isBlank() ? (code == null ? "HTTP " + status : code) : SocialHttp.clip(message));
        if (code == null) code = "";
        switch (code) {
            case "access_token_invalid", "scope_not_authorized", "scope_permission_missed" -> {
                return new SocialException(Kind.RECONNECT, "TikTok sign-in expired or lacks a permission. Reconnect TikTok.");
            }
            case "rate_limit_exceeded" -> {
                return new SocialException(Kind.RATE_LIMITED, text, SocialHttp.retryAfter(r, Duration.ofMinutes(1)));
            }
            case "spam_risk_too_many_posts" -> {
                return new SocialException(Kind.RATE_LIMITED, "TikTok's daily post limit for this account is reached; will try again later.", Duration.ofHours(6));
            }
            case "reached_active_user_cap" -> {
                return new SocialException(Kind.RATE_LIMITED, "The TikTok app reached its daily limit of posting users; will try again later.", Duration.ofHours(6));
            }
            case "unaudited_client_can_only_post_to_private_accounts" -> {
                return new SocialException(Kind.REJECTED, "TikTok only lets unaudited apps post to a private account. Set the TikTok account to private, or get the app audited.");
            }
            case "privacy_level_option_mismatch" -> {
                return new SocialException(Kind.REJECTED, "That privacy setting is not available for this TikTok account any more. Choose it again.");
            }
            case "spam_risk_user_banned_from_posting" -> {
                return new SocialException(Kind.REJECTED, "TikTok has blocked this account from posting.");
            }
            default -> {
                // status based
            }
        }
        if (status == 401) return new SocialException(Kind.RECONNECT, "TikTok sign-in expired. Reconnect TikTok.");
        if (status == 429) return new SocialException(Kind.RATE_LIMITED, text, SocialHttp.retryAfter(r, Duration.ofMinutes(1)));
        if (status >= 500) return new SocialException(Kind.TEMPORARY, text);
        return new SocialException(Kind.REJECTED, text);
    }
}
