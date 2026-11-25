package com.leenax.studio.publish;

import com.leenax.studio.social.MetaClient;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialException.Kind;
import com.leenax.studio.social.SocialPlatform;

import java.time.Duration;
import java.time.Instant;

/** Page Reels: video_reels start → rupload → finish (PUBLISHED), then video status until live. */
public class FacebookPublisher implements Publisher {

    static final Duration PROCESSING_LIMIT = Duration.ofHours(3);

    private final MetaClient meta;

    public FacebookPublisher(MetaClient meta) {
        this.meta = meta;
    }

    @Override
    public SocialPlatform platform() {
        return SocialPlatform.FACEBOOK;
    }

    @Override
    public Outcome start(Job job) {
        String pageId = job.account().getExternalId();
        String token = job.token();
        String videoId = job.stateText("videoId");
        boolean resumed = videoId != null;
        if (videoId == null) {
            MetaClient.UploadSession session = meta.reelStart(pageId, token);
            videoId = session.videoId();
            job.state().put("videoId", videoId);
            job.state().put("uploadUrl", session.uploadUrl());
            job.checkpoint("Uploading to Facebook", 0.05);
        }
        if (!job.done("uploaded")) {
            long offset = 0;
            if (resumed) {
                MetaClient.VideoStatus st = meta.videoStatus(videoId, token);
                offset = "complete".equals(st.uploadStatus()) ? job.size() : Math.min(st.bytesTransferred(), job.size());
            }
            if (offset < job.size()) meta.rupload(job.stateText("uploadUrl"), token, job.video(), job.size(), offset);
            job.state().put("uploaded", true);
            job.checkpoint("Uploaded to Facebook", 0.75);
        }
        if (!job.done("finished")) {
            meta.reelFinish(pageId, token, videoId, job.setting("description"), job.setting("title"));
            job.state().put("finished", true);
            job.since("processingSince");
            job.checkpoint("Facebook is processing the reel", 0.85);
        }
        return Outcome.Wait.processing(Duration.ofSeconds(20), "Facebook is processing the reel");
    }

    @Override
    public Outcome poll(Job job) {
        String videoId = job.stateText("videoId");
        MetaClient.VideoStatus s = meta.videoStatus(videoId, job.token());
        if ("error".equals(s.videoStatus()) || "error".equals(s.processingStatus()) || "error".equals(s.publishingStatus())) {
            throw new SocialException(Kind.REJECTED, "Facebook could not publish the reel"
                    + (s.errorMessage() != null ? ": " + s.errorMessage() : "."));
        }
        if ("complete".equals(s.publishingStatus())) {
            String url = s.permalink() != null ? s.permalink() : "https://www.facebook.com/reel/" + videoId;
            return new Outcome.Published(videoId, url, null);
        }
        if (job.since("processingSince").plus(PROCESSING_LIMIT).isBefore(Instant.now())) {
            throw new SocialException(Kind.REJECTED, "Facebook had not published the reel after three hours. Check the Page's content library.");
        }
        return Outcome.Wait.processing(Duration.ofSeconds(30), "Facebook is processing the reel");
    }
}
