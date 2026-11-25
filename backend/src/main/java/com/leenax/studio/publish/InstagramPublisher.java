package com.leenax.studio.publish;

import com.leenax.studio.social.MetaClient;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialException.Kind;
import com.leenax.studio.social.SocialPlatform;

import java.time.Duration;
import java.time.Instant;

/** Instagram Reels: resumable container → rupload → status_code FINISHED → media_publish. */
public class InstagramPublisher implements Publisher {

    static final Duration PROCESSING_LIMIT = Duration.ofHours(1);

    private final MetaClient meta;

    public InstagramPublisher(MetaClient meta) {
        this.meta = meta;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.INSTAGRAM;
    }

    @Override
    public Outcome start(Job job) {
        String igId = job.account().getExternalId();
        String token = job.token();
        String container = job.stateText("containerId");
        if (container == null) {
            MetaClient.PublishingLimit limit = meta.igPublishingLimit(igId, token);
            if (limit.used() >= limit.total()) {
                throw new SocialException(Kind.RATE_LIMITED, "Instagram allows " + limit.total()
                        + " API posts per 24 hours and this account has used them; will try again later.", Duration.ofHours(1));
            }
            Object cover = job.settings().get("coverSeconds");
            Long thumbOffset = cover instanceof Number n ? Math.round(n.doubleValue() * 1000) : null;
            container = meta.igCreateReel(igId, token, job.setting("caption"), job.setting("shareToFeed", true), thumbOffset);
            job.state().put("containerId", container);
            job.checkpoint("Uploading to Instagram", 0.05);
        }
        if (!job.done("uploaded")) {
            meta.rupload(meta.igUploadUrl(container), token, job.video(), job.size(), 0);
            job.state().put("uploaded", true);
            job.since("processingSince");
            job.checkpoint("Instagram is processing the reel", 0.8);
        }
        return Outcome.Wait.processing(Duration.ofSeconds(20), "Instagram is processing the reel");
    }

    @Override
    public Outcome poll(Job job) {
        String token = job.token();
        String mediaId = job.stateText("mediaId");
        if (mediaId == null) {
            String container = job.stateText("containerId");
            MetaClient.ContainerStatus s = meta.igContainerStatus(container, token);
            switch (s.code()) {
                case "FINISHED" -> {
                    mediaId = meta.igPublish(job.account().getExternalId(), token, container);
                    job.state().put("mediaId", mediaId);
                    job.checkpoint("Published on Instagram", 0.97);
                }
                case "PUBLISHED" -> {
                    return new Outcome.Published(container, null, "Instagram reports the reel as published, but did not return its link.");
                }
                case "ERROR" -> throw new SocialException(Kind.REJECTED, "Instagram could not process the video"
                        + (s.detail() != null ? ": " + s.detail() : "."));
                case "EXPIRED" -> {
                    // Containers last 24 hours: start over with a fresh one.
                    job.state().clear();
                    job.checkpoint("Instagram upload expired; uploading again", 0);
                    return new Outcome.Wait(PostTarget.Status.SCHEDULED, Duration.ofSeconds(5), "Uploading to Instagram again", 0);
                }
                default -> {
                    if (job.since("processingSince").plus(PROCESSING_LIMIT).isBefore(Instant.now())) {
                        throw new SocialException(Kind.REJECTED, "Instagram was still processing the video after an hour. Try a smaller H.264/AAC MP4.");
                    }
                    return Outcome.Wait.processing(Duration.ofSeconds(30), "Instagram is processing the reel");
                }
            }
        }
        return new Outcome.Published(mediaId, meta.igPermalink(mediaId, token), null);
    }
}
