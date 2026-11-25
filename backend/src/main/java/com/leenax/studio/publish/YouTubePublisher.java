package com.leenax.studio.publish;

import com.leenax.studio.ai.provider.MediaTypes;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialException.Kind;
import com.leenax.studio.social.SocialPlatform;
import com.leenax.studio.social.YouTubeClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resumable videos.insert, optional thumbnails.set, then videos.list until processed. */
public class YouTubePublisher implements Publisher {

    static final Duration PROCESSING_LIMIT = Duration.ofHours(2);
    static final String PRIVATE_LOCK_WARNING = "YouTube kept this video private. Uploads from Google Cloud projects that "
            + "have not passed YouTube's API audit are locked to private; change it in YouTube Studio if the channel allows.";

    private final YouTubeClient youtube;

    public YouTubePublisher(YouTubeClient youtube) {
        this.youtube = youtube;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.YOUTUBE;
    }

    static Map<String, Object> metadata(Job job) {
        Map<String, Object> snippet = new LinkedHashMap<>();
        snippet.put("title", job.setting("title"));
        snippet.put("description", job.setting("description"));
        Object tags = job.settings().get("tags");
        if (tags instanceof java.util.List<?> l && !l.isEmpty()) snippet.put("tags", l);
        snippet.put("categoryId", job.setting("categoryId").isBlank() ? "22" : job.setting("categoryId"));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("privacyStatus", job.setting("privacy"));
        if (!job.setting("publishAt").isBlank()) status.put("publishAt", job.setting("publishAt"));
        status.put("selfDeclaredMadeForKids", job.setting("madeForKids", false));
        status.put("containsSyntheticMedia", job.setting("aiGenerated", false));
        return Map.of("snippet", snippet, "status", status);
    }

    @Override
    public Outcome start(Job job) {
        String token = job.token();
        String videoId = job.stateText("videoId");
        if (videoId == null) {
            String session = job.stateText("sessionUrl");
            boolean resume = session != null;
            if (session == null) {
                session = youtube.startUpload(token, metadata(job), job.size(), job.contentType());
                job.state().put("sessionUrl", session);
                job.checkpoint("Uploading to YouTube", 0.05);
            }
            try {
                videoId = youtube.upload(session, job.video(), job.size(), job.contentType(), token, resume);
            } catch (SocialException e) {
                if (YouTubeClient.SESSION_EXPIRED.equals(e.getMessage())) job.state().remove("sessionUrl");
                throw e;
            }
            job.state().put("videoId", videoId);
            job.state().remove("sessionUrl");
            job.checkpoint("Uploaded to YouTube", 0.8);
        }
        if (!job.done("thumbnailTried")) {
            MediaTypes.Sample thumb = job.thumbnail();
            if (thumb != null) {
                try {
                    youtube.setThumbnail(token, videoId, thumb.bytes(), thumb.mimeType());
                } catch (SocialException e) {
                    job.state().put("thumbnailWarning", "Custom thumbnail not set (" + e.getMessage()
                            + "). YouTube allows custom thumbnails only on verified channels.");
                }
            }
            job.state().put("thumbnailTried", true);
            job.checkpoint("Thumbnail done", 0.82);
        }
        extras(job, videoId);
        job.since("processingSince");
        return Outcome.Wait.processing(Duration.ofSeconds(30), "YouTube is processing the video");
    }

    /**
     * E9: playlists and caption files. Each is done once and remembered. A refusal (often a
     * channel connected before these permissions were asked for) becomes a warning, since the
     * video itself is up; a quota or network error is retried later like the upload.
     */
    void extras(Job job, String videoId) {
        String token = job.token();
        Object ids = job.settings().get("playlistIds");
        if (ids instanceof java.util.List<?> list) {
            for (Object o : list) {
                String key = "playlist:" + o;
                if (job.done(key)) continue;
                try {
                    youtube.addToPlaylist(token, String.valueOf(o), videoId);
                } catch (SocialException e) {
                    if (retryLater(e)) throw e;
                    warn(job, "Not added to a playlist (" + e.getMessage() + ").");
                }
                job.state().put(key, true);
                job.checkpoint("Added to playlists", 0.83);
            }
        }
        Object caps = job.settings().get("captions");
        if (caps instanceof java.util.List<?> list) {
            int i = 0;
            for (Object o : list) {
                String key = "caption:" + i++;
                if (job.done(key) || !(o instanceof Map<?, ?> m)) continue;
                try {
                    youtube.uploadCaption(token, videoId, String.valueOf(m.get("language")), String.valueOf(m.get("name")),
                            String.valueOf(m.get("srt")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (SocialException e) {
                    if (retryLater(e)) throw e;
                    warn(job, "Captions not uploaded (" + e.getMessage() + ").");
                }
                job.state().put(key, true);
                job.checkpoint("Captions uploaded", 0.84);
            }
        }
    }

    /** Quota and network trouble: the step is tried again later (what's done is remembered). */
    private static boolean retryLater(SocialException e) {
        return e.kind() == SocialException.Kind.RATE_LIMITED || e.kind() == SocialException.Kind.TEMPORARY;
    }

    private static void warn(Job job, String text) {
        String w = job.stateText("extrasWarning");
        job.state().put("extrasWarning", w == null ? text : w + " " + text);
    }

    @Override
    public Outcome poll(Job job) {
        String videoId = job.stateText("videoId");
        YouTubeClient.VideoStatus s = youtube.status(job.token(), videoId);
        String upload = s.uploadStatus() == null ? "" : s.uploadStatus();
        switch (upload) {
            case "processed" -> {
                return published(job, videoId, s, null);
            }
            case "rejected" -> throw new SocialException(Kind.REJECTED, "YouTube rejected the video" + reason(s.rejectionReason()));
            case "failed" -> throw new SocialException(Kind.REJECTED, "YouTube could not process the video" + reason(s.failureReason()));
            case "deleted" -> throw new SocialException(Kind.REJECTED, "The video was deleted on YouTube");
            default -> {
                if (job.since("processingSince").plus(PROCESSING_LIMIT).isBefore(Instant.now())) {
                    return published(job, videoId, s, "YouTube was still processing the video after two hours; it will appear when done.");
                }
                return Outcome.Wait.processing(Duration.ofSeconds(60), "YouTube is processing the video");
            }
        }
    }

    private static Outcome published(Job job, String videoId, YouTubeClient.VideoStatus s, String note) {
        StringBuilder warning = new StringBuilder();
        String requested = job.setting("privacy");
        if (!"private".equals(requested) && "private".equals(s.privacyStatus())) warning.append(PRIVATE_LOCK_WARNING);
        if (job.stateText("thumbnailWarning") != null) append(warning, job.stateText("thumbnailWarning"));
        if (job.stateText("extrasWarning") != null) append(warning, job.stateText("extrasWarning"));
        if (note != null) append(warning, note);
        return new Outcome.Published(videoId, "https://www.youtube.com/watch?v=" + videoId,
                warning.isEmpty() ? null : warning.toString());
    }

    private static void append(StringBuilder b, String s) {
        if (!b.isEmpty()) b.append(' ');
        b.append(s);
    }

    private static String reason(String r) {
        return r == null ? "." : " (" + r + ").";
    }
}
