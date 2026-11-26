
package com.leenax.studio.publish;

import com.leenax.studio.ai.provider.MediaTypes;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.social.MetaClient;
import com.leenax.studio.social.SocialAccount;
import com.leenax.studio.social.SocialAccountService;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialPlatform;
import com.leenax.studio.social.TikTokClient;
import com.leenax.studio.social.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runs one step of one target: upload (start) or a processing check (poll). */
@Component
public class PublishProcessor {

    private static final Logger log = LoggerFactory.getLogger(PublishProcessor.class);
    static final int MAX_ATTEMPTS = 6;
    static final long YOUTUBE_THUMBNAIL_BYTES = 2L * 1024 * 1024;

    private final PublishStore store;
    private final SocialAccountService accounts;
    private final MediaService media;
    private final AuditService audit;
    private final Map<SocialPlatform, Publisher> publishers = new EnumMap<>(SocialPlatform.class);

    @Autowired
    public PublishProcessor(PublishStore store, SocialAccountService accounts, MediaService media, AuditService audit,
                            YouTubeClient youtube, MetaClient meta, TikTokClient tiktok) {
        this(store, accounts, media, audit, List.of(
                new YouTubePublisher(youtube),
                new FacebookPublisher(meta),
                new InstagramPublisher(meta),
                new TikTokPublisher(tiktok)
        ));
    }

    PublishProcessor(PublishStore store, SocialAccountService accounts, MediaService media, AuditService audit,
                     List<Publisher> list) {
        this.store = store;
        this.accounts = accounts;
        this.media = media;
        this.audit = audit;
        for (Publisher p : list) {
            publishers.put(p.platform(), p);
        }
    }

    public void process(UUID targetId) {
        PublishStore.Snapshot snap = store.begin(targetId).orElse(null);
        if (snap == null) return;

        Map<String, Object> state = snap.state();
        Job job = null;

        try {
            if (snap.accountId() == null) {
                store.failed(targetId, state, snap.attempts(), "The account was disconnected");
                return;
            }

            SocialAccount account = accounts.find(snap.accountId());
            MediaAsset asset = media.findAsset(snap.mediaId());
            UUID thumbId = snap.thumbnailMediaId();

            job = new Job(
                    account,
                    snap.settings(),
                    state,
                    asset.getSizeBytes(),
                    asset.getContentType(),
                    asset.getDurationMs(),
                    () -> accounts.accessToken(account.getId()),
                    () -> media.localCopy(
                            asset.getId(),
                            Set.of(MediaKind.VIDEO),
                            "The post's file is not a video"
                    ).path(),
                    thumbId == null ? null : () -> thumbnail(thumbId),
                    (s, step, progress) -> store.checkpoint(targetId, s, step, progress)
            );

            Publisher publisher = publishers.get(account.getPlatform());
            boolean upload = snap.status() == PostTarget.Status.SCHEDULED
                    || snap.status() == PostTarget.Status.UPLOADING;

            Outcome outcome = upload ? publisher.start(job) : publisher.poll(job);

            if (outcome instanceof Outcome.Published p) {
                store.published(targetId, state, p);
                audit.record(
                        "POST_PUBLISHED",
                        "post_target",
                        targetId,
                        Map.of(
                                "platform", account.getPlatform().name(),
                                "url", p.url() == null ? "" : p.url()
                        )
                );
            } else if (outcome instanceof Outcome.Wait w) {
                store.waitFor(targetId, state, w);
            }

        } catch (PublishStore.Cancelled e) {
            log.info("Post target {} was cancelled while running", targetId);

        } catch (SocialException e) {
            if (e.kind() == SocialException.Kind.RECONNECT && snap.accountId() != null) {
                accounts.flagReconnect(snap.accountId(), e.getMessage());
            }
            handle(targetId, state, snap.attempts(), e);

        } catch (ApiException e) {
            store.failed(targetId, state, snap.attempts() + 1, e.getMessage());

        } catch (RuntimeException e) {
            log.error("Publishing target {} crashed", targetId, e);
            handle(
                    targetId,
                    state,
                    snap.attempts(),
                    new SocialException(
                            SocialException.Kind.TEMPORARY,
                            "Unexpected error while publishing"
                    )
            );

        } finally {
            if (job != null) {
                job.cleanup();
            }
        }
    }

    private void handle(UUID targetId, Map<String, Object> state, int attempts, SocialException e) {
        int next = attempts + 1;

        if (e.retryable() && next < MAX_ATTEMPTS) {
            Duration delay = e.retryAfter() != null ? e.retryAfter() : backoff(next);
            store.retryLater(targetId, state, next, delay, e.getMessage());
            return;
        }

        String message = e.retryable()
                ? "Gave up after " + next + " tries. " + e.getMessage()
                : e.getMessage();

        store.failed(targetId, state, next, message);
        audit.record("POST_FAILED", "post_target", targetId, Map.of("reason", e.kind().name()));
    }

    /** 30 s, 1 min, 2 min, 4 min… capped at one hour. */
    static Duration backoff(int attempt) {
        long seconds = 30L << Math.min(Math.max(attempt - 1, 0), 7);
        return Duration.ofSeconds(Math.min(seconds, 3600));
    }

    private MediaTypes.Sample thumbnail(UUID id) {
        try {
            return media.sample(
                    id,
                    Set.of(MediaKind.IMAGE),
                    YOUTUBE_THUMBNAIL_BYTES,
                    "The thumbnail is not an image"
            );
        } catch (ApiException e) {
            return null; // too large or gone: publish without it
        }
    }
}
