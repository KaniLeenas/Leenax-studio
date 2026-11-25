package com.leenax.studio.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.social.SocialException.Kind;
import com.leenax.studio.social.SocialHttp.Response;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Google sign-in (OAuth 2.0 with PKCE) and the YouTube Data API v3:
 * channels.list, resumable videos.insert, videos.list and thumbnails.set.
 */
public class YouTubeClient {

    /**
     * upload and readonly (Phase 6); force-ssl for playlists and captions, and yt-analytics.readonly
     * for the Analytics and Reporting APIs (E9). Channels connected before E9 are asked to reconnect.
     */
    public static final String SCOPES = "https://www.googleapis.com/auth/youtube.upload https://www.googleapis.com/auth/youtube.readonly"
            + " https://www.googleapis.com/auth/youtube.force-ssl https://www.googleapis.com/auth/yt-analytics.readonly";
    public static final String SCOPE_MANAGE = "https://www.googleapis.com/auth/youtube.force-ssl";
    public static final String SCOPE_ANALYTICS = "https://www.googleapis.com/auth/yt-analytics.readonly";

    /** Whether a connection's granted scopes include these. */
    public static boolean granted(String scopes, String... needed) {
        java.util.Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList((scopes == null ? "" : scopes).split("[\\s,]+")));
        for (String n : needed) if (!have.contains(n)) return false;
        return true;
    }
    private static final String NAME = "YouTube";
    /** The upload session is gone; the publisher starts a new one. */
    public static final String SESSION_EXPIRED = "YouTube upload session expired; starting a new one";

    private final SocialHttp http;
    private final SocialProperties props;

    public YouTubeClient(SocialHttp http, SocialProperties props) {
        this.http = http;
        this.props = props;
    }

    // ---- sign-in ---------------------------------------------------------

    public String authorizationUrl(String state, String codeChallenge) {
        requireConfigured();
        return props.endpoints().googleAuth() + "?" + SocialHttp.encode(SocialHttp.params(
                "client_id", props.google().clientId(),
                "redirect_uri", props.redirectUri(SocialPlatform.AuthProvider.GOOGLE),
                "response_type", "code",
                "scope", SCOPES,
                "access_type", "offline",      // ask for a refresh token
                "prompt", "consent",           // ...every time, so reconnecting always yields one
                "include_granted_scopes", "true",
                "state", state,
                "code_challenge", codeChallenge,
                "code_challenge_method", "S256"));
    }

    public TokenSet exchangeCode(String code, String codeVerifier) {
        requireConfigured();
        return tokenCall(SocialHttp.params(
                "code", code,
                "client_id", props.google().clientId(),
                "client_secret", props.google().clientSecret(),
                "redirect_uri", props.redirectUri(SocialPlatform.AuthProvider.GOOGLE),
                "grant_type", "authorization_code",
                "code_verifier", codeVerifier));
    }

    public TokenSet refresh(String refreshToken) {
        requireConfigured();
        if (refreshToken == null) throw new SocialException(Kind.RECONNECT, "Google did not give a refresh token. Reconnect YouTube.");
        return tokenCall(SocialHttp.params(
                "refresh_token", refreshToken,
                "client_id", props.google().clientId(),
                "client_secret", props.google().clientSecret(),
                "grant_type", "refresh_token"));
    }

    private TokenSet tokenCall(Map<String, String> form) {
        Response r = http.send(SocialHttp.request(props.endpoints().googleToken() + "/token", Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(SocialHttp.form(form)).build(), NAME);
        if (!r.ok()) throw oauthError(r);
        String access = r.text("/access_token");
        if (access == null) throw new SocialException(Kind.TEMPORARY, "Google sent no access token");
        long expires = r.json().path("expires_in").asLong(3600);
        return new TokenSet(access, r.text("/refresh_token"), Instant.now().plusSeconds(expires), null, r.text("/scope"));
    }

    /** Best effort: the account is removed locally whatever Google answers. */
    public void revoke(String token) {
        if (token == null) return;
        try {
            http.send(SocialHttp.request(props.endpoints().googleToken() + "/revoke", Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(SocialHttp.form(SocialHttp.params("token", token))).build(), NAME);
        } catch (SocialException ignored) {
            // offline: the grant can also be removed at myaccount.google.com/permissions
        }
    }

    public Profile channel(String accessToken) {
        Response r = get("/channels?part=snippet&mine=true", accessToken);
        JsonNode item = r.json().path("items").path(0);
        if (item.isMissingNode()) {
            throw new SocialException(Kind.REJECTED, "This Google account has no YouTube channel. Create one on youtube.com, then connect again.");
        }
        JsonNode s = item.path("snippet");
        return new Profile(SocialPlatform.YOUTUBE, item.path("id").asText(), s.path("title").asText("YouTube channel"),
                s.path("customUrl").asText(null), s.at("/thumbnails/default/url").asText(null));
    }

    /** Channel details for the publishing page. Subscriber counts are null when the channel hides them. */
    public record ChannelInfo(String id, String title, String customUrl, String thumbnail, Long subscribers, Long videos, Long views,
                              boolean subscribersHidden) {
    }

    public ChannelInfo channelInfo(String accessToken) {
        Response r = get("/channels?part=snippet,statistics&mine=true", accessToken);
        JsonNode item = r.json().path("items").path(0);
        if (item.isMissingNode()) throw new SocialException(Kind.REJECTED, "This Google account has no YouTube channel.");
        JsonNode s = item.path("snippet"), st = item.path("statistics");
        boolean hidden = st.path("hiddenSubscriberCount").asBoolean(false);
        return new ChannelInfo(item.path("id").asText(), s.path("title").asText(""), s.path("customUrl").asText(null),
                s.at("/thumbnails/default/url").asText(null), hidden ? null : num(st, "subscriberCount"), num(st, "videoCount"),
                num(st, "viewCount"), hidden);
    }

    // ---- upload ------------------------------------------------------------

    /** Opens a resumable upload session (videos.insert) and returns its upload URL. */
    public String startUpload(String accessToken, Map<String, Object> metadata, long size, String contentType) {
        Response r = http.send(SocialHttp.request(props.endpoints().youtubeUpload()
                        + "/videos?uploadType=resumable&part=snippet,status", Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Length", Long.toString(size))
                .header("X-Upload-Content-Type", contentType)
                .POST(http.json(metadata)).build(), NAME);
        if (!r.ok()) throw apiError(r);
        String location = r.header("location");
        if (location == null) throw new SocialException(Kind.TEMPORARY, "YouTube did not open an upload session");
        return location;
    }

    /**
     * Sends the file to an upload session, resuming from wherever YouTube says it
     * got to. Returns the new video id.
     */
    public String upload(String sessionUrl, Path file, long size, String contentType, String accessToken, boolean resume) {
        long offset = 0;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0 || resume) {
                Progress p = progress(sessionUrl, size, accessToken);
                if (p.videoId() != null) return p.videoId();  // already complete
                offset = p.nextByte();
            }
            HttpRequest.Builder b = SocialHttp.request(sessionUrl, Duration.ofHours(3))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", contentType)
                    .PUT(SocialHttp.filePart(file, offset, size - offset));
            if (offset > 0) b.header("Content-Range", "bytes " + offset + "-" + (size - 1) + "/" + size);
            Response r;
            try {
                r = http.send(b.build(), NAME);
            } catch (SocialException e) {
                if (e.kind() == Kind.TEMPORARY) continue;   // network dropped: ask where to resume
                throw e;
            }
            if (r.ok()) {
                String id = r.text("/id");
                if (id == null) throw new SocialException(Kind.TEMPORARY, "YouTube finished the upload without a video id");
                return id;
            }
            if (r.status() == 308 || r.status() >= 500) continue;
            if (r.status() == 404 || r.status() == 410) {
                throw new SocialException(Kind.TEMPORARY, SESSION_EXPIRED);
            }
            throw apiError(r);
        }
        throw new SocialException(Kind.TEMPORARY, "YouTube upload kept getting interrupted");
    }

    /** How far an upload session got: the next byte to send, or the video id once it is complete. */
    public record Progress(long nextByte, String videoId) {
    }

    public Progress progress(String sessionUrl, long size, String accessToken) {
        Response r = http.send(SocialHttp.request(sessionUrl, Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Range", "bytes */" + size)
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), NAME);
        if (r.ok()) {
            return new Progress(size, r.text("/id"));
        }
        if (r.status() == 308) {
            String range = r.header("range");                 // "bytes=0-12345"
            if (range == null) return new Progress(0, null);
            return new Progress(Long.parseLong(range.substring(range.indexOf('-') + 1).strip()) + 1, null);
        }
        if (r.status() == 404 || r.status() == 410) {
            throw new SocialException(Kind.TEMPORARY, SESSION_EXPIRED);
        }
        throw apiError(r);
    }

    public record VideoStatus(String uploadStatus, String privacyStatus, String failureReason, String rejectionReason,
                              String processingStatus) {
    }

    public VideoStatus status(String accessToken, String videoId) {
        Response r = get("/videos?part=status,processingDetails&id=" + URLEncoder.encode(videoId, StandardCharsets.UTF_8), accessToken);
        JsonNode item = r.json().path("items").path(0);
        if (item.isMissingNode()) return new VideoStatus("deleted", null, null, null, null);
        JsonNode s = item.path("status");
        return new VideoStatus(s.path("uploadStatus").asText(null), s.path("privacyStatus").asText(null),
                s.path("failureReason").asText(null), s.path("rejectionReason").asText(null),
                item.at("/processingDetails/processingStatus").asText(null));
    }

    /** videos.list statistics for up to 50 videos: views, likes, comments. */
    public Map<String, Counts> statistics(String accessToken, java.util.List<String> videoIds) {
        Map<String, Counts> out = new java.util.HashMap<>();
        for (int i = 0; i < videoIds.size(); i += 50) {
            String ids = String.join(",", videoIds.subList(i, Math.min(videoIds.size(), i + 50)));
            Response r = get("/videos?part=statistics&id=" + URLEncoder.encode(ids, StandardCharsets.UTF_8), accessToken);
            for (JsonNode item : r.json().path("items")) {
                JsonNode st = item.path("statistics");
                out.put(item.path("id").asText(), new Counts(num(st, "viewCount"), num(st, "likeCount"), num(st, "commentCount"),
                        null, null, null, Map.of()));
            }
        }
        return out;
    }

    // ---- research (E6): public search results and video numbers ------------------------------

    /**
     * search.list: the top video results for a query (100 quota units; the caller keeps the
     * daily budget). Only ids come back; {@link #videos} fills in the public numbers.
     */
    public java.util.List<String> search(String accessToken, String query, String language, String region, int max) {
        StringBuilder q = new StringBuilder("/search?part=id&type=video&safeSearch=moderate&order=relevance&maxResults=")
                .append(Math.max(1, Math.min(50, max))).append("&q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        if (language != null && language.matches("[a-z]{2}")) q.append("&relevanceLanguage=").append(language);
        if (region != null && region.matches("[A-Z]{2}")) q.append("&regionCode=").append(region);
        Response r = get(q.toString(), accessToken);
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : r.json().path("items")) {
            String id = item.path("id").path("videoId").asText("");
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    /** Public details of a video, as videos.list returns them. Hidden counts stay null. */
    public record VideoInfo(String id, String title, String channelId, String channelTitle, String publishedAt, String description,
                            java.util.List<String> tags, Long durationSeconds, String thumbnail, Long views, Long likes, Long comments,
                            String language) {
    }

    /** videos.list with snippet, contentDetails and statistics: 1 quota unit per call of up to 50 videos. */
    public java.util.List<VideoInfo> videos(String accessToken, java.util.List<String> ids) {
        java.util.List<VideoInfo> out = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i += 50) {
            String batch = String.join(",", ids.subList(i, Math.min(ids.size(), i + 50)));
            Response r = get("/videos?part=snippet,contentDetails,statistics&id=" + URLEncoder.encode(batch, StandardCharsets.UTF_8), accessToken);
            for (JsonNode item : r.json().path("items")) out.add(videoInfo(item));
        }
        return out;
    }

    static VideoInfo videoInfo(JsonNode item) {
        JsonNode sn = item.path("snippet"), st = item.path("statistics");
        java.util.List<String> tags = new java.util.ArrayList<>();
        sn.path("tags").forEach(t -> tags.add(t.asText()));
        JsonNode thumbs = sn.path("thumbnails");
        String thumb = thumbs.path("high").path("url").asText(thumbs.path("medium").path("url").asText(thumbs.path("default").path("url").asText(null)));
        String lang = sn.path("defaultAudioLanguage").asText(sn.path("defaultLanguage").asText(null));
        return new VideoInfo(item.path("id").asText(), sn.path("title").asText(""), sn.path("channelId").asText(null),
                sn.path("channelTitle").asText(""), sn.path("publishedAt").asText(null), sn.path("description").asText(""), tags,
                isoSeconds(item.path("contentDetails").path("duration").asText(null)), thumb, num(st, "viewCount"), num(st, "likeCount"),
                num(st, "commentCount"), lang);
    }

    /** ISO 8601 durations as YouTube sends them (PT1H2M3S, P1DT2H): seconds, or null. */
    static Long isoSeconds(String d) {
        if (d == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").matcher(d);
        if (!m.matches() || d.equals("P") || d.equals("PT")) return null;
        long s = 0;
        long[] mult = {86400, 3600, 60, 1};
        for (int g = 1; g <= 4; g++) if (m.group(g) != null) s += Long.parseLong(m.group(g)) * mult[g - 1];
        return s;
    }

    /** YouTube sends counts as strings, and leaves a count out when the owner hides it. */
    static Long num(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        try {
            return Long.parseLong(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- playlists and captions (E9) --------------------------------------------------------------

    public record Playlist(String id, String title, String privacy, long items) {
    }

    /** playlists.list mine=true (1 unit a page of 50). */
    public java.util.List<Playlist> playlists(String accessToken) {
        java.util.List<Playlist> out = new java.util.ArrayList<>();
        String page = null;
        for (int i = 0; i < 10; i++) {
            Response r = get("/playlists?part=snippet,status,contentDetails&mine=true&maxResults=50" + (page == null ? "" : "&pageToken="
                    + URLEncoder.encode(page, StandardCharsets.UTF_8)), accessToken);
            for (JsonNode p : r.json().path("items")) {
                out.add(new Playlist(p.path("id").asText(), p.path("snippet").path("title").asText(""), p.path("status").path("privacyStatus").asText(null),
                        p.path("contentDetails").path("itemCount").asLong(0)));
            }
            page = r.json().path("nextPageToken").asText(null);
            if (page == null) break;
        }
        return out;
    }

    /** playlists.insert (50 units). */
    public Playlist createPlaylist(String accessToken, String title, String description, String privacy) {
        Map<String, Object> body = Map.of("snippet", Map.of("title", title, "description", description == null ? "" : description),
                "status", Map.of("privacyStatus", privacy));
        Response r = http.send(SocialHttp.request(props.endpoints().youtubeApi() + "/playlists?part=snippet,status", Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .POST(http.json(body)).build(), NAME);
        if (!r.ok()) throw apiError(r);
        return new Playlist(r.json().path("id").asText(), title, privacy, 0);
    }

    /** playlistItems.insert (50 units). */
    public void addToPlaylist(String accessToken, String playlistId, String videoId) {
        Map<String, Object> body = Map.of("snippet", Map.of("playlistId", playlistId, "resourceId", Map.of("kind", "youtube#video", "videoId", videoId)));
        Response r = http.send(SocialHttp.request(props.endpoints().youtubeApi() + "/playlistItems?part=snippet", Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .POST(http.json(body)).build(), NAME);
        if (!r.ok()) throw apiError(r);
    }

    /** captions.insert (400 units): a multipart upload of the caption file with its snippet. */
    public String uploadCaption(String accessToken, String videoId, String language, String name, byte[] file) {
        String boundary = "leenax" + java.util.UUID.randomUUID().toString().replace("-", "");
        String meta;
        try {
            meta = http.mapper().writeValueAsString(Map.of("snippet", Map.of("videoId", videoId, "language", language, "name", name, "isDraft", false)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta + "\r\n--" + boundary
                + "\r\nContent-Type: application/octet-stream\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Response r = http.send(SocialHttp.request(props.endpoints().youtubeUpload() + "/captions?uploadType=multipart&part=snippet", Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "multipart/related; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), NAME);
        if (!r.ok()) throw apiError(r);
        return r.json().path("id").asText(null);
    }

    /** thumbnails.set. Needs a verified channel for custom thumbnails. */
    public void setThumbnail(String accessToken, String videoId, byte[] image, String contentType) {
        Response r = http.send(SocialHttp.request(props.endpoints().youtubeUpload() + "/thumbnails/set?videoId="
                        + URLEncoder.encode(videoId, StandardCharsets.UTF_8), Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(image)).build(), NAME);
        if (!r.ok()) throw apiError(r);
    }

    private Response get(String pathAndQuery, String accessToken) {
        Response r = http.send(SocialHttp.request(props.endpoints().youtubeApi() + pathAndQuery, Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json").GET().build(), NAME);
        if (!r.ok()) throw apiError(r);
        return r;
    }

    private void requireConfigured() {
        if (!props.google().configured()) {
            throw new SocialException(Kind.NOT_CONFIGURED, "Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET to connect YouTube.");
        }
    }

    // ---- errors --------------------------------------------------------------

    static SocialException oauthError(Response r) {
        String error = r.text("/error");
        String desc = SocialHttp.clip(r.text("/error_description"));
        if ("invalid_grant".equals(error)) {
            return new SocialException(Kind.RECONNECT, "Google sign-in expired or was revoked. Reconnect YouTube.");
        }
        if ("invalid_client".equals(error) || "unauthorized_client".equals(error)) {
            return new SocialException(Kind.NOT_CONFIGURED, "Google rejected the client id or secret" + (desc == null ? "." : ": " + desc));
        }
        if (r.status() >= 500 || r.status() == 429) return new SocialException(Kind.TEMPORARY, "Google sign-in is unavailable right now");
        return new SocialException(Kind.REJECTED, "Google sign-in failed" + (desc == null ? "." : ": " + desc));
    }

    static SocialException apiError(Response r) {
        String message = SocialHttp.clip(r.text("/error/message"));
        String reason = r.text("/error/errors/0/reason");
        String text = "YouTube: " + (message == null ? "HTTP " + r.status() : message);
        if (reason != null) {
            switch (reason) {
                case "quotaExceeded", "dailyLimitExceeded" -> {
                    return new SocialException(Kind.RATE_LIMITED, "YouTube API daily quota is used up; will try again later.", Duration.ofHours(3));
                }
                case "uploadLimitExceeded" -> {
                    return new SocialException(Kind.RATE_LIMITED, "This channel reached YouTube's daily upload limit; will try again later.", Duration.ofHours(6));
                }
                case "rateLimitExceeded", "userRateLimitExceeded" -> {
                    return new SocialException(Kind.RATE_LIMITED, text, SocialHttp.retryAfter(r, Duration.ofMinutes(5)));
                }
                case "insufficientPermissions", "authError" -> {
                    return new SocialException(Kind.RECONNECT, "YouTube permission is missing. Reconnect YouTube and allow uploads.");
                }
                case "youtubeSignupRequired" -> {
                    return new SocialException(Kind.REJECTED, "This Google account has no YouTube channel yet.");
                }
                default -> {
                    // fall through to the status-based mapping
                }
            }
        }
        if (r.status() == 401) return new SocialException(Kind.RECONNECT, "YouTube sign-in expired. Reconnect YouTube.");
        if (r.status() == 429) return new SocialException(Kind.RATE_LIMITED, text, SocialHttp.retryAfter(r, Duration.ofMinutes(5)));
        if (r.status() >= 500) return new SocialException(Kind.TEMPORARY, text);
        return new SocialException(Kind.REJECTED, text);
    }
}
