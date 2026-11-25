package com.leenax.studio.publish;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Short transactions the worker uses while a target is being published, so no
 * database transaction stays open during an upload.
 */
@Service
public class PublishStore {

    /** The user cancelled the target while it was running. */
    public static class Cancelled extends RuntimeException {
        public Cancelled() {
            super("Cancelled");
        }
    }

    public record Snapshot(UUID id, UUID postId, UUID accountId, PostTarget.Status status, Map<String, Object> settings,
                           Map<String, Object> state, int attempts, UUID mediaId, UUID thumbnailMediaId) {
    }

    private final PostRepository posts;
    private final PostTargetRepository targets;
    private final Authorisations authorisations;

    public PublishStore(PostRepository posts, PostTargetRepository targets, Authorisations authorisations) {
        this.posts = posts;
        this.targets = targets;
        this.authorisations = authorisations;
    }

    @Transactional
    public Optional<Snapshot> begin(UUID targetId) {
        PostTarget t = targets.findById(targetId).orElse(null);
        if (t == null || !t.isActive()) return Optional.empty();
        Post p = posts.findById(t.getPostId()).orElse(null);
        if (p == null) return Optional.empty();
        if (t.getStatus() == PostTarget.Status.SCHEDULED) {
            if (!authorisations.authorised(t.getId())) {
                // the database would refuse the upload too; say why instead of trying again
                t.setStatus(PostTarget.Status.FAILED);
                t.setRunAt(null);
                t.setErrorMessage("Not authorised: the file or settings changed after they were authorised, or nobody authorised them. Authorise it again.");
                targets.save(t);
                rollup(p.getId());
                return Optional.empty();
            }
            t.setStatus(PostTarget.Status.UPLOADING);
            t.setStep("Preparing");
            targets.save(t);
            rollup(p.getId());
        }
        return Optional.of(new Snapshot(t.getId(), p.getId(), t.getAccountId(), t.getStatus(), new HashMap<>(t.getSettings()),
                new HashMap<>(t.getState()), t.getAttempts(), p.getMediaId(), p.getThumbnailMediaId()));
    }

    @Transactional
    public void checkpoint(UUID targetId, Map<String, Object> state, String step, double progress) {
        PostTarget t = live(targetId);
        t.setState(state);
        t.setStep(step);
        t.setProgress(progress);
        targets.save(t);
    }

    @Transactional
    public void published(UUID targetId, Map<String, Object> state, Outcome.Published o) {
        PostTarget t = targets.findById(targetId).orElseThrow();
        t.setState(state);
        t.setStatus(PostTarget.Status.PUBLISHED);
        t.setRemoteId(o.remoteId());
        t.setRemoteUrl(o.url());
        t.setWarning(o.warning());
        t.setErrorMessage(null);
        t.setStep(null);
        t.setProgress(1);
        t.setPublishedAt(Instant.now());
        t.setRunAt(null);
        t.setLockedUntil(null);
        targets.save(t);
        rollup(t.getPostId());
    }

    @Transactional
    public void waitFor(UUID targetId, Map<String, Object> state, Outcome.Wait w) {
        PostTarget t = live(targetId);
        t.setState(state);
        t.setStatus(w.status());
        t.setStep(w.step());
        t.setProgress(w.progress());
        t.setRunAt(w.after() == null ? null : Instant.now().plus(w.after()));
        t.setLockedUntil(null);
        targets.save(t);
        rollup(t.getPostId());
    }

    /** A retryable problem: try again after {@code delay}. */
    @Transactional
    public void retryLater(UUID targetId, Map<String, Object> state, int attempts, Duration delay, String message) {
        PostTarget t = live(targetId);
        t.setState(state);
        t.setAttempts(attempts);
        t.setErrorMessage(message);
        t.setStep("Will try again");
        t.setRunAt(Instant.now().plus(delay));
        t.setLockedUntil(null);
        targets.save(t);
    }

    @Transactional
    public void failed(UUID targetId, Map<String, Object> state, int attempts, String message) {
        PostTarget t = targets.findById(targetId).orElse(null);
        if (t == null) return;
        if (state != null) t.setState(state);
        t.setAttempts(attempts);
        t.setStatus(PostTarget.Status.FAILED);
        t.setErrorMessage(message);
        t.setStep(null);
        t.setRunAt(null);
        t.setLockedUntil(null);
        targets.save(t);
        rollup(t.getPostId());
    }

    private PostTarget live(UUID targetId) {
        PostTarget t = targets.findById(targetId).orElseThrow(Cancelled::new);
        if (t.getStatus() == PostTarget.Status.CANCELLED) throw new Cancelled();
        return t;
    }

    /** Recomputes the post's overall status from its targets. */
    @Transactional
    public void rollup(UUID postId) {
        Post p = posts.findById(postId).orElse(null);
        if (p == null) return;
        List<PostTarget> all = targets.findByPostIdOrderByCreatedAtAsc(postId);
        Post.Status s = rollup(p.getStatus(), all.stream().map(PostTarget::getStatus).toList());
        p.setStatus(s);
        if (s == Post.Status.PUBLISHED || s == Post.Status.PARTIAL) {
            p.setPublishedAt(all.stream().map(PostTarget::getPublishedAt).filter(java.util.Objects::nonNull)
                    .max(Instant::compareTo).orElse(Instant.now()));
        }
        posts.save(p);
    }

    /**
     * Overall status: anything still to do wins, then success, then failure.
     * A TikTok draft sent to the app counts as done on our side.
     */
    static Post.Status rollup(Post.Status current, List<PostTarget.Status> statuses) {
        if (statuses.isEmpty()) return current == Post.Status.DRAFT ? Post.Status.DRAFT : Post.Status.CANCELLED;
        if (statuses.stream().allMatch(s -> s == PostTarget.Status.DRAFT)) return Post.Status.DRAFT;
        if (statuses.stream().anyMatch(PostTarget.Status.RUNNING::contains)) return Post.Status.PUBLISHING;
        boolean anyScheduled = statuses.contains(PostTarget.Status.SCHEDULED);
        boolean anyDone = statuses.stream().anyMatch(s -> s == PostTarget.Status.PUBLISHED || s == PostTarget.Status.AWAITING_USER);
        boolean anyFailed = statuses.contains(PostTarget.Status.FAILED);
        if (anyScheduled) return anyDone || anyFailed ? Post.Status.PUBLISHING : Post.Status.SCHEDULED;
        if (anyDone) return anyFailed ? Post.Status.PARTIAL : Post.Status.PUBLISHED;
        if (anyFailed) return Post.Status.FAILED;
        return Post.Status.CANCELLED;
    }
}
