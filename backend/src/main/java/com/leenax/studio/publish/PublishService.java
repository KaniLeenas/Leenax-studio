package com.leenax.studio.publish;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.publish.PublishDtos.Action;
import com.leenax.studio.publish.PublishDtos.PostRequest;
import com.leenax.studio.publish.PublishDtos.PostResponse;
import com.leenax.studio.publish.PublishDtos.TargetRequest;
import com.leenax.studio.publish.PublishDtos.TargetResponse;
import com.leenax.studio.social.SocialAccount;
import com.leenax.studio.social.SocialAccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Drafts, scheduling, publishing now, rescheduling from the calendar, cancelling and retrying. */
@Service
public class PublishService {

    static final Duration MAX_AHEAD = Duration.ofDays(366);

    private final PostRepository posts;
    private final PostTargetRepository targets;
    private final PublishStore store;
    private final SocialAccountService accounts;
    private final MediaService media;
    private final AuditService audit;
    private final Authorisations authorisations;

    public PublishService(PostRepository posts, PostTargetRepository targets, PublishStore store,
                          SocialAccountService accounts, MediaService media, AuditService audit, Authorisations authorisations) {
        this.authorisations = authorisations;
        this.posts = posts;
        this.targets = targets;
        this.store = store;
        this.accounts = accounts;
        this.media = media;
        this.audit = audit;
    }

    // ---- read ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PostResponse> list(Post.Status status) {
        return responses(status == null ? posts.findTop50ByOrderByUpdatedAtDesc() : posts.findTop100ByStatusOrderByUpdatedAtDesc(status));
    }

    @Transactional(readOnly = true)
    public List<PostResponse> calendar(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) throw ApiException.badRequest("Give a from and to date");
        if (Duration.between(from, to).toDays() > 62) throw ApiException.badRequest("The calendar can load at most 62 days at once");
        return responses(posts.findInRange(from, to));
    }

    @Transactional(readOnly = true)
    public PostResponse get(UUID id) {
        return responses(List.of(find(id))).get(0);
    }

    // ---- write -------------------------------------------------------------------

    @Transactional
    public PostResponse create(PostRequest req) {
        return create(req, null);
    }

    /** Creates a post; for a lesson or quiz video its authorisation also names the project and approved version. */
    @Transactional
    public PostResponse create(PostRequest req, Authorisations.Education education) {
        Post post = new Post(req.title().strip(), req.mediaId(), req.thumbnailMediaId());
        post = posts.saveAndFlush(post);
        List<PostTarget> built = build(post, req);
        targets.saveAll(built);
        apply(post, req, built, education);
        audit.record("POST_CREATED", "post", post.getId(), Map.of("action", req.action().name(), "targets", built.size()));
        return get(post.getId());
    }

    @Transactional
    public PostResponse update(UUID id, PostRequest req) {
        Post post = find(id);
        List<PostTarget> existing = targets.findByPostIdOrderByCreatedAtAsc(id);
        if (existing.stream().anyMatch(t -> !PostTarget.Status.EDITABLE.contains(t.getStatus()))) {
            throw new ApiException(ErrorCode.CONFLICT, "This post is already publishing or published, so it can't be edited. Duplicate it instead.");
        }
        if (authorisations.education(id)) {
            throw new ApiException(ErrorCode.CONFLICT, "This lesson video is published from its project's Publish page. Cancel it there and start again.");
        }
        post.setTitle(req.title().strip());
        post.setMediaId(req.mediaId());
        post.setThumbnailMediaId(req.thumbnailMediaId());
        List<PostTarget> built = build(post, req);
        targets.deleteAll(existing);
        targets.flush();
        targets.saveAll(built);
        apply(post, req, built, null);
        audit.record("POST_UPDATED", "post", id, Map.of("action", req.action().name()));
        return get(id);
    }

    private void apply(Post post, PostRequest req, List<PostTarget> built, Authorisations.Education education) {
        Instant now = Instant.now();
        switch (req.action()) {
            case DRAFT -> {
                post.setScheduledAt(req.scheduledAt());
                built.forEach(PostTarget::toDraft);
            }
            case NOW -> {
                post.setScheduledAt(now);
                built.forEach(t -> t.schedule(now));
            }
            case SCHEDULE -> {
                Instant at = checkTime(req.scheduledAt());
                post.setScheduledAt(at);
                built.forEach(t -> t.schedule(at));
            }
        }
        posts.save(post);
        targets.saveAll(built);
        targets.flush();
        if (req.action() != Action.DRAFT) {
            // posting or scheduling is the person's authorisation of exactly these targets
            for (PostTarget t : built) {
                boolean pub = Authorisations.isPublic(t.getPlatform(), t.getSettings());
                if (pub && !Boolean.TRUE.equals(req.confirmPublic())) throw Authorisations.notConfirmed();
                authorisations.authorise(t.getId(), pub, education, Authorisations.statement(t.getPlatform(), t.getAccountName(), t.getSettings(), pub));
            }
        }
        store.rollup(post.getId());
    }

    static Instant checkTime(Instant at) {
        Instant now = Instant.now();
        if (at == null) throw ApiException.badRequest("Choose a date and time to schedule the post");
        if (at.isBefore(now.minusSeconds(60))) throw ApiException.badRequest("That time has already passed");
        if (at.isAfter(now.plus(MAX_AHEAD))) throw ApiException.badRequest("Posts can be scheduled up to a year ahead");
        return at.isBefore(now) ? now : at;
    }

    /** Validates media, accounts and every platform's settings; returns unsaved targets. */
    private List<PostTarget> build(Post post, PostRequest req) {
        MediaAsset asset = media.findAsset(req.mediaId());
        if (asset.getKind() != MediaKind.VIDEO) throw ApiException.badRequest("Choose a video to publish");
        if (req.thumbnailMediaId() != null && media.findAsset(req.thumbnailMediaId()).getKind() != MediaKind.IMAGE) {
            throw ApiException.badRequest("The thumbnail must be an image");
        }
        if (req.targets().isEmpty() && req.action() != Action.DRAFT) throw ApiException.badRequest("Choose at least one account");
        PlatformRules.MediaFacts facts = new PlatformRules.MediaFacts(asset.getSizeBytes(), asset.getDurationMs(),
                asset.getWidth(), asset.getHeight());
        List<String> errors = new ArrayList<>();
        List<PostTarget> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (TargetRequest tr : req.targets()) {
            if (!seen.add(tr.accountId())) continue;
            SocialAccount a = accounts.find(tr.accountId());
            if (req.action() != Action.DRAFT && a.getStatus() != SocialAccount.Status.CONNECTED) {
                errors.add(a.getDisplayName() + " needs to be reconnected before posting");
            }
            Map<String, Object> settings = req.action() == Action.DRAFT
                    ? lenient(a, tr.settings(), facts)
                    : PlatformRules.normalize(a.getPlatform(), tr.settings(), facts, errors);
            out.add(new PostTarget(post.getId(), a.getId(), a.getPlatform(), a.getDisplayName(), settings));
        }
        if (!errors.isEmpty()) throw ApiException.badRequest(String.join(" · ", errors));
        return out;
    }

    /** Drafts may be incomplete; keep what was typed, cleaned, without failing. */
    private static Map<String, Object> lenient(SocialAccount a, Map<String, Object> raw, PlatformRules.MediaFacts facts) {
        Map<String, Object> clean = PlatformRules.normalize(a.getPlatform(), raw, facts, new ArrayList<>());
        Map<String, Object> out = new LinkedHashMap<>(clean);
        if (raw != null && raw.get("musicConsent") == null) out.remove("musicConsent");
        if (raw != null && raw.get("madeForKids") == null) out.remove("madeForKids");
        return out;
    }

    /** Calendar drag-and-drop: moves a draft's planned date or a scheduled post's time. */
    @Transactional
    public PostResponse reschedule(UUID id, Instant at) {
        Post post = find(id);
        List<PostTarget> list = targets.findByPostIdOrderByCreatedAtAsc(id);
        if (post.getStatus() == Post.Status.DRAFT) {
            post.setScheduledAt(at);
        } else if (post.getStatus() == Post.Status.SCHEDULED) {
            Instant when = checkTime(at);
            post.setScheduledAt(when);
            list.stream().filter(t -> t.getStatus() == PostTarget.Status.SCHEDULED).forEach(t -> t.schedule(when));
            targets.saveAll(list);
        } else {
            throw new ApiException(ErrorCode.CONFLICT, "Only drafts and scheduled posts can be moved");
        }
        posts.save(post);
        audit.record("POST_RESCHEDULED", "post", id, Map.of("at", at.toString()));
        return get(id);
    }

    /** Stops targets that haven't started. Uploads already under way finish. */
    @Transactional
    public PostResponse cancel(UUID id) {
        find(id);
        List<PostTarget> list = targets.findByPostIdOrderByCreatedAtAsc(id);
        int n = 0;
        for (PostTarget t : list) {
            if (t.getStatus() == PostTarget.Status.SCHEDULED || t.getStatus() == PostTarget.Status.DRAFT) {
                t.setStatus(PostTarget.Status.CANCELLED);
                t.setRunAt(null);
                n++;
            }
        }
        if (n == 0) throw new ApiException(ErrorCode.CONFLICT, "Nothing left to cancel: the uploads have already started");
        targets.saveAll(list);
        targets.flush();
        store.rollup(id);
        audit.record("POST_CANCELLED", "post", id, Map.of("targets", n));
        return get(id);
    }

    /** Publishes a failed or cancelled target again, from the start. */
    @Transactional
    public PostResponse retry(UUID postId, UUID targetId) {
        return retry(postId, targetId, false);
    }

    /**
     * Tries a failed or cancelled target again. It keeps its authorisation; a target that never
     * had one (scheduled before authorisation records existed) is authorised by this request.
     */
    @Transactional
    public PostResponse retry(UUID postId, UUID targetId, boolean confirmPublic) {
        PostTarget t = targets.findById(targetId).filter(x -> x.getPostId().equals(postId))
                .orElseThrow(() -> ApiException.notFound("Post target"));
        if (t.getStatus() != PostTarget.Status.FAILED && t.getStatus() != PostTarget.Status.CANCELLED) {
            throw new ApiException(ErrorCode.CONFLICT, "Only failed or cancelled posts can be retried");
        }
        if (t.getAccountId() == null) throw new ApiException(ErrorCode.CONFLICT, "That account was disconnected. Connect it again and make a new post.");
        SocialAccount a = accounts.find(t.getAccountId());
        if (a.getStatus() != SocialAccount.Status.CONNECTED) {
            throw new ApiException(ErrorCode.CONFLICT, a.getDisplayName() + " needs to be reconnected first");
        }
        if (!authorisations.authorised(t.getId())) {
            boolean pub = Authorisations.isPublic(t.getPlatform(), t.getSettings());
            if (pub && !confirmPublic) throw Authorisations.notConfirmed();
            authorisations.authorise(t.getId(), pub, null, Authorisations.statement(t.getPlatform(), t.getAccountName(), t.getSettings(), pub));
        }
        t.schedule(Instant.now());
        targets.saveAndFlush(t);
        store.rollup(postId);
        audit.record("POST_RETRIED", "post_target", targetId, Map.of());
        return get(postId);
    }

    /** Removes the post from the studio. Posts already on the platforms stay there. */
    @Transactional
    public void delete(UUID id) {
        find(id);
        List<PostTarget> list = targets.findByPostIdOrderByCreatedAtAsc(id);
        if (list.stream().anyMatch(t -> PostTarget.Status.RUNNING.contains(t.getStatus()))) {
            throw new ApiException(ErrorCode.CONFLICT, "Wait for the uploads in progress to finish first");
        }
        targets.deleteAll(list);
        posts.deleteById(id);
        audit.record("POST_DELETED", "post", id, Map.of());
    }

    // ---- helpers ------------------------------------------------------------------

    private Post find(UUID id) {
        return posts.findById(id).orElseThrow(() -> ApiException.notFound("Post"));
    }

    private List<PostResponse> responses(List<Post> list) {
        if (list.isEmpty()) return List.of();
        Map<UUID, List<PostTarget>> byPost = targets.findByPostIdInOrderByCreatedAtAsc(list.stream().map(Post::getId).toList())
                .stream().collect(Collectors.groupingBy(PostTarget::getPostId));
        Map<UUID, MediaResponse> mediaById = media.findAssets(list.stream().map(Post::getMediaId)
                        .filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(MediaAsset::getId, media::toResponse, (a, b) -> a));
        return list.stream().map(p -> new PostResponse(p.getId(), p.getTitle(), mediaById.get(p.getMediaId()), p.getThumbnailMediaId(),
                p.getStatus(), p.getScheduledAt(), p.getPublishedAt(), p.getCreatedAt(), p.getUpdatedAt(), p.getVersion(),
                byPost.getOrDefault(p.getId(), List.of()).stream().map(TargetResponse::of).toList())).toList();
    }
}
