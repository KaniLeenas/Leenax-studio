package com.leenax.studio.publish;

import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialException.Kind;
import com.leenax.studio.social.SocialPlatform;
import com.leenax.studio.social.TikTokClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Content Posting API with FILE_UPLOAD. Direct Post re-reads the creator info
 * just before posting (TikTok requires it) and checks the chosen privacy and
 * the video length against it. Inbox mode hands the draft to the TikTok app.
 */
public class TikTokPublisher implements Publisher {

    static final Duration PROCESSING_LIMIT = Duration.ofHours(2);
    static final Duration INBOX_LIMIT = Duration.ofHours(24);
    static final String INBOX_STEP = "Open TikTok and finish the post from your inbox notification";

    private final TikTokClient tiktok;

    public TikTokPublisher(TikTokClient tiktok) {
        this.tiktok = tiktok;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.TIKTOK;
    }

    boolean inbox(Job job) {
        return "INBOX".equals(job.setting("mode"));
    }

    /** post_info for Direct Post, checked against the creator's current options. */
    static Map<String, Object> postInfo(Job job, TikTokClient.CreatorInfo c) {
        String privacy = job.setting("privacy");
        if (!c.privacyOptions().contains(privacy)) {
            throw new SocialException(Kind.REJECTED, "The chosen TikTok privacy (" + privacy + ") is not available for this account now. "
                    + "Edit the post and choose again.");
        }
        if (c.maxVideoSeconds() > 0 && job.durationMs() != null && job.durationMs() / 1000.0 > c.maxVideoSeconds()) {
            throw new SocialException(Kind.REJECTED, "This TikTok account can post videos up to " + c.maxVideoSeconds() + " seconds.");
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", job.setting("caption"));
        info.put("privacy_level", privacy);
        info.put("disable_comment", c.commentDisabled() || !job.setting("allowComment", false));
        info.put("disable_duet", c.duetDisabled() || !job.setting("allowDuet", false));
        info.put("disable_stitch", c.stitchDisabled() || !job.setting("allowStitch", false));
        info.put("brand_content_toggle", job.setting("brandContent", false));
        info.put("brand_organic_toggle", job.setting("brandOrganic", false));
        info.put("is_aigc", job.setting("aiGenerated", false));
        Object cover = job.settings().get("coverSeconds");
        if (cover instanceof Number n) info.put("video_cover_timestamp_ms", Math.round(n.doubleValue() * 1000));
        return info;
    }

    @Override
    public Outcome start(Job job) {
        String token = job.token();
        if (job.stateText("publishId") == null || !job.done("uploaded")) {
            Map<String, Object> info = null;
            if (!inbox(job)) {
                TikTokClient.CreatorInfo c = tiktok.creatorInfo(token);
                info = postInfo(job, c);
                if (c.username() != null) job.state().put("username", c.username());
            }
            // The upload link lives one hour, so an unfinished earlier attempt starts again.
            TikTokClient.Init init = tiktok.init(token, info, job.size());
            job.state().put("publishId", init.publishId());
            job.checkpoint("Uploading to TikTok", 0.05);
            tiktok.upload(init.uploadUrl(), job.video(), job.size(), job.contentType());
            job.state().put("uploaded", true);
            job.since("processingSince");
            job.checkpoint("TikTok is processing the video", 0.85);
        }
        return Outcome.Wait.processing(Duration.ofSeconds(15), "TikTok is processing the video");
    }

    @Override
    public Outcome poll(Job job) {
        TikTokClient.Status s = tiktok.status(job.token(), job.stateText("publishId"));
        switch (s.status()) {
            case "PUBLISH_COMPLETE" -> {
                String username = job.stateText("username") != null ? job.stateText("username") : job.account().getUsername();
                if (!s.postIds().isEmpty()) {
                    String id = s.postIds().get(0);
                    String url = username == null ? null : "https://www.tiktok.com/@" + username + "/video/" + id;
                    return new Outcome.Published(id, url, null);
                }
                return new Outcome.Published(job.stateText("publishId"), null,
                        "Posted. TikTok has not made it public (it is private, or still in review), so there is no public link yet.");
            }
            case "SEND_TO_USER_INBOX" -> {
                Instant since = job.since("inboxSince");
                Duration next = since.plus(INBOX_LIMIT).isBefore(Instant.now()) ? null : Duration.ofMinutes(15);
                return new Outcome.Wait(PostTarget.Status.AWAITING_USER, next, INBOX_STEP, 1);
            }
            case "FAILED" -> throw new SocialException(Kind.REJECTED, "TikTok could not post the video"
                    + (s.failReason() != null ? " (" + s.failReason() + ")." : "."));
            default -> {
                if (job.since("processingSince").plus(PROCESSING_LIMIT).isBefore(Instant.now())) {
                    throw new SocialException(Kind.REJECTED, "TikTok was still processing the video after two hours.");
                }
                return Outcome.Wait.processing(Duration.ofSeconds(20), "TikTok is processing the video");
            }
        }
    }
}
