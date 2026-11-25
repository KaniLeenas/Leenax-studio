package com.leenax.studio.approvals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.ContentHandler;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.approvals.Review.Decision;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.Review.Origin;
import com.leenax.studio.approvals.Review.Status;
import com.leenax.studio.approvals.ReviewDtos.EventResponse;
import com.leenax.studio.approvals.ReviewDtos.ResourceInfo;
import com.leenax.studio.approvals.ReviewDtos.ResourceState;
import com.leenax.studio.approvals.ReviewDtos.VersionDetail;
import com.leenax.studio.approvals.ReviewDtos.VersionSummary;
import com.leenax.studio.approvals.ReviewDtos.You;
import com.leenax.studio.approvals.ReviewStore.ResourceRow;
import com.leenax.studio.approvals.ReviewStore.VersionRow;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.auth.CurrentUser;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.projects.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The approval engine (E3). Every decision names one exact version and is recorded with
 * who, when, the status before and after, and the comment. Content never changes once a
 * version leaves draft: editing makes a new version, and everything that depends on an
 * approved resource becomes Outdated when it is edited. PostgreSQL holds the same rules
 * (V11), so a mistake here is refused there.
 */
@Service
public class ReviewService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EDIT_MESSAGE = "Only the project's owners and editors can change this";

    /** What each kind depends on: editing an approved syllabus outdates the topic list. */
    static final Map<Kind, Kind> DEPENDS_ON = Map.of(Kind.TOPICS, Kind.SYLLABUS);

    private final ReviewStore store;
    private final ProjectService projects;
    private final CurrentUser current;
    private final AuditService audit;
    private final Map<Kind, ContentHandler> handlers = new EnumMap<>(Kind.class);
    private final List<Review.Gate> gates;

    public ReviewService(ReviewStore store, ProjectService projects, CurrentUser current, AuditService audit, List<ContentHandler> handlers,
                         List<Review.Gate> gates) {
        this.gates = gates == null ? List.of() : gates;
        this.store = store;
        this.projects = projects;
        this.current = current;
        this.audit = audit;
        handlers.forEach(h -> this.handlers.put(h.kind(), h));
    }

    // ---- reading -----------------------------------------------------------------------------------

    public ResourceState state(UUID projectId, Kind kind) {
        projects.requireAccess(projectId, false, null);
        boolean canEdit = projects.canEdit(projectId);
        boolean canReview = projects.canReview(projectId);
        Optional<ResourceRow> resource = store.resource(projectId, kind);
        if (resource.isEmpty()) return new ResourceState(kind, null, List.of(), null, null, null, canEdit, canReview);
        ResourceRow r = resource.get();
        List<VersionRow> versions = store.versions(r.id());
        Optional<VersionRow> approved = versions.stream().filter(v -> v.status() == Status.APPROVED).findFirst();
        VersionDetail latest = versions.isEmpty() ? null : detail(store.version(versions.get(0).id()).orElseThrow(), r, true);
        return new ResourceState(kind, info(r), versions.stream().map(VersionSummary::of).toList(), latest,
                approved.map(VersionRow::id).orElse(null), approved.map(VersionRow::versionNo).orElse(null), canEdit, canReview);
    }

    /** Any resource's state by its id (notes and question banks, of which there are many). */
    public ResourceState stateOf(UUID resourceId) {
        ResourceRow r = findResource(resourceId);
        projects.requireAccess(r.projectId(), false, null);
        List<VersionRow> versions = store.versions(r.id());
        Optional<VersionRow> approved = versions.stream().filter(v -> v.status() == Status.APPROVED).findFirst();
        VersionDetail latest = versions.isEmpty() ? null : detail(store.version(versions.get(0).id()).orElseThrow(), r, true);
        return new ResourceState(r.kind(), info(r), versions.stream().map(VersionSummary::of).toList(), latest,
                approved.map(VersionRow::id).orElse(null), approved.map(VersionRow::versionNo).orElse(null),
                projects.canEdit(r.projectId()), projects.canReview(r.projectId()));
    }

    public VersionDetail version(UUID versionId) {
        VersionRow v = findVersion(versionId);
        ResourceRow r = findResource(v.resourceId());
        projects.requireAccess(r.projectId(), false, null);
        return detail(v, r, isLatest(v));
    }

    public List<EventResponse> events(UUID resourceId) {
        ResourceRow r = findResource(resourceId);
        projects.requireAccess(r.projectId(), false, null);
        return store.events(resourceId).stream().map(e -> new EventResponse(e.id(), e.versionId(), e.versionNo(), e.action(),
                e.previousStatus(), e.newStatus(), e.comment(), readJson(e.details()), e.actorId(), e.actorName(), e.createdAt())).toList();
    }

    /** The approved version's id of a kind in a project, if there is one. */
    public Optional<VersionRow> approved(UUID projectId, Kind kind) {
        return store.resource(projectId, kind).flatMap(r -> store.approved(r.id()));
    }

    // ---- making versions ---------------------------------------------------------------------------

    /**
     * What a new version belongs to. For notes and banks: the topic (and level), a title for
     * lists, and the resources it depends on (linked when the resource is first made).
     */
    public record Target(UUID projectId, Kind kind, String topicId, String level, String title, List<UUID> dependsOnResources) {

        public static Target of(UUID projectId, Kind kind) {
            return new Target(projectId, kind, null, null, kind.label(), List.of());
        }
    }

    /** The syllabus or topic list version; see {@link #createVersion(Target, JsonNode, Origin, UUID, UUID, String)}. */
    @Transactional
    public VersionRow createVersion(UUID projectId, Kind kind, JsonNode content, Origin origin, UUID basedOn, UUID dependsOn,
                                    String note) {
        List<UUID> links = new java.util.ArrayList<>();
        Kind parent = DEPENDS_ON.get(kind);
        if (parent != null) store.resource(projectId, parent).ifPresent(p -> links.add(p.id()));
        return createVersion(new Target(projectId, kind, null, null, kind.label(), links), content, origin, basedOn, dependsOn, note);
    }

    /**
     * A new draft version (creating the resource on first use). The caller has checked who may
     * do this; background jobs call it as the person who started them. When the resource
     * has an approved version, everything built on it becomes Outdated.
     */
    @Transactional
    public VersionRow createVersion(Target t, JsonNode content, Origin origin, UUID basedOn, UUID dependsOn, String note) {
        ContentHandler handler = handler(t.kind());
        JsonNode clean = handler.normalise(content, dependsOn);
        ResourceRow r = store.resource(t.projectId(), t.kind(), t.topicId(), t.level()).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            store.createResource(id, t.projectId(), t.kind(), t.title() == null ? t.kind().label() : clip(t.title(), 200), t.topicId(), t.level());
            t.dependsOnResources().forEach(p -> store.link(id, p));
            return store.resource(id).orElseThrow();
        });
        if (t.title() != null && !t.title().isBlank()) store.retitle(r.id(), clip(t.title(), 200));
        t.dependsOnResources().forEach(p -> store.link(r.id(), p));
        Optional<VersionRow> approved = store.approved(r.id());
        if (approved.isPresent()) outdateDependents(r, approved.get());

        String canonical = Review.canonical(clean);
        int no = store.nextVersionNo(r.id());
        UUID id = UUID.randomUUID();
        store.insertVersion(id, r.id(), no, Status.DRAFT, origin, canonical, Review.hash(canonical), basedOn, dependsOn);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("origin", origin.name());
        if (note != null) details.put("note", note);
        store.event(t.projectId(), r.id(), id, "CREATED", null, Status.DRAFT, null, json(details));
        store.touch(r.id());
        return store.version(id).orElseThrow();
    }

    /**
     * Replaces a draft's content (the background jobs use this to fill or extend a draft
     * the person asked them to). The caller has checked who may.
     */
    @Transactional
    public VersionRow replaceDraft(VersionRow v, JsonNode content, String note) {
        ResourceRow r = findResource(v.resourceId());
        JsonNode clean = handler(r.kind()).normalise(content, v.dependsOn());
        String canonical = Review.canonical(clean);
        if (store.saveDraft(v.id(), canonical, Review.hash(canonical), v.dependsOn()) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Version " + v.versionNo() + " isn't a draft any more.");
        }
        if (note != null) store.event(r.projectId(), r.id(), v.id(), "CREATED", Status.DRAFT, Status.DRAFT, note, null);
        store.touch(r.id());
        return store.version(v.id()).orElseThrow();
    }

    /** Edits a draft in place. */
    @Transactional
    public VersionDetail saveDraft(UUID versionId, JsonNode content) {
        VersionRow v = findVersion(versionId);
        ResourceRow r = findResource(v.resourceId());
        requireEdit(r);
        if (v.status() != Status.DRAFT) {
            throw new ApiException(ErrorCode.CONFLICT, "Version " + v.versionNo() + " is " + label(v.status()) + ", so it can't be changed. Edit it to make a new version.");
        }
        if (!handler(r.kind()).editableByHand()) throw handMade(r);
        JsonNode clean = handler(r.kind()).normalise(content, v.dependsOn());
        String canonical = Review.canonical(clean);
        if (store.saveDraft(versionId, canonical, Review.hash(canonical), v.dependsOn()) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "This version changed meanwhile. Reload and try again.");
        }
        store.touch(r.id());
        return version(versionId);
    }

    /**
     * A new draft from an earlier version, after it was approved, sent back or rejected.
     * One draft at a time, and nothing new while a version is in review.
     */
    @Transactional
    public VersionDetail edit(UUID resourceId, UUID fromVersionId, JsonNode content) {
        ResourceRow r = findResource(resourceId);
        requireEdit(r);
        if (!handler(r.kind()).editableByHand()) throw handMade(r);
        VersionRow from = findVersion(fromVersionId);
        if (!from.resourceId().equals(resourceId)) throw ApiException.notFound("Version");
        VersionRow newest = store.versions(resourceId).get(0);
        if (newest.status() == Status.DRAFT) {
            throw new ApiException(ErrorCode.CONFLICT, "Version " + newest.versionNo() + " is already a draft. Change that one.");
        }
        if (newest.status() == Status.IN_REVIEW) {
            throw new ApiException(ErrorCode.CONFLICT, "Version " + newest.versionNo() + " is in review. Withdraw it first to change it.");
        }
        JsonNode body = content != null ? content : readJson(store.version(fromVersionId).orElseThrow().content());
        VersionRow made = createVersion(new Target(r.projectId(), r.kind(), r.topicId(), r.level(), null, List.of()), body,
                Origin.EDIT, from.id(), from.dependsOn(), "Edit of version " + from.versionNo());
        return detail(made, r, true);
    }

    // ---- review ------------------------------------------------------------------------------------

    @Transactional
    public VersionDetail submit(UUID versionId, String comment) {
        VersionRow v = findVersion(versionId);
        ResourceRow r = findResource(v.resourceId());
        requireEdit(r);
        requireLatest(v);
        if (v.status() != Status.DRAFT) throw conflict("Only a draft can be sent for review; this is " + label(v.status()) + ".");
        List<Check> checks = checks(r, readJson(v.content()));
        if (!Review.passes(checks)) throw conflict("Fix these first: " + failing(checks));
        moveOrConflict(v, Status.DRAFT, Status.IN_REVIEW);
        store.event(r.projectId(), r.id(), v.id(), "SUBMITTED", Status.DRAFT, Status.IN_REVIEW, blankToNull(comment), null);
        audit.record("REVIEW_SUBMITTED", "resource", r.id(), Map.of("kind", r.kind().name(), "version", v.versionNo()));
        return version(versionId);
    }

    /** Back to draft, to change it before anyone decides. */
    @Transactional
    public VersionDetail withdraw(UUID versionId) {
        VersionRow v = findVersion(versionId);
        ResourceRow r = findResource(v.resourceId());
        requireEdit(r);
        if (v.status() != Status.IN_REVIEW) throw conflict("Only a version in review can be withdrawn.");
        moveOrConflict(v, Status.IN_REVIEW, Status.DRAFT);
        store.event(r.projectId(), r.id(), v.id(), "WITHDRAWN", Status.IN_REVIEW, Status.DRAFT, null, null);
        return version(versionId);
    }

    @Transactional
    public VersionDetail decide(UUID versionId, Decision decision, String comment, JsonNode edited) {
        VersionRow v = findVersion(versionId);
        ResourceRow r = findResource(v.resourceId());
        projects.requireAccess(r.projectId(), false, null);
        if (!projects.canReview(r.projectId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the project's owners and reviewers can approve, reject or ask for changes.");
        }
        UUID me = current.require().getId();
        if (store.reviewerMustDiffer(r.projectId()) && me.equals(v.createdBy())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You wrote version " + v.versionNo()
                    + ", and in this project the reviewer must not be the author. Ask another reviewer.");
        }
        if (v.status() != Status.IN_REVIEW) throw conflict("Version " + v.versionNo() + " isn't in review; it's " + label(v.status()) + ".");
        if (decision.needsComment() && (comment == null || comment.isBlank())) {
            throw ApiException.badRequest("Say what needs to change, so the author can act on it.");
        }
        String note = blankToNull(comment);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("contentHash", v.contentHash());

        switch (decision) {
            case APPROVE -> {
                requireChecks(r, readJson(v.content()));
                archiveApproved(r, "Replaced by version " + v.versionNo());
                moveOrConflict(v, Status.IN_REVIEW, Status.APPROVED);
                store.event(r.projectId(), r.id(), v.id(), "APPROVED", Status.IN_REVIEW, Status.APPROVED, note, json(details));
            }
            case APPROVE_WITH_EDITS -> {
                if (edited == null) throw ApiException.badRequest("Send the edited content to approve with edits.");
                if (!handler(r.kind()).editableByHand()) throw handMade(r);
                JsonNode clean = handler(r.kind()).normalise(edited, v.dependsOn());
                String canonical = Review.canonical(clean);
                String hash = Review.hash(canonical);
                if (hash.equals(v.contentHash())) return decide(versionId, Decision.APPROVE, comment, null);
                requireChecks(r, clean);
                List<String> changes = handler(r.kind()).diff(readJson(v.content()), clean);
                int no = store.nextVersionNo(r.id());
                archiveApproved(r, "Replaced by version " + no);
                moveOrConflict(v, Status.IN_REVIEW, Status.ARCHIVED);
                store.event(r.projectId(), r.id(), v.id(), "ARCHIVED", Status.IN_REVIEW, Status.ARCHIVED,
                        "Approved with the reviewer's edits as version " + no, null);
                UUID id = UUID.randomUUID();
                store.insertVersion(id, r.id(), no, Status.APPROVED, Origin.REVIEWER, canonical, hash, v.id(), v.dependsOn());
                details.put("contentHash", hash);
                details.put("reviewedVersion", v.versionNo());
                details.put("reviewedHash", v.contentHash());
                details.put("changes", changes);
                store.event(r.projectId(), r.id(), id, "APPROVED_WITH_EDITS", null, Status.APPROVED, note, json(details));
                audit.record("REVIEW_APPROVED", "resource", r.id(), Map.of("kind", r.kind().name(), "version", no, "withEdits", true));
                store.touch(r.id());
                return version(id);
            }
            case REQUEST_CHANGES, REJECT -> {
                moveOrConflict(v, Status.IN_REVIEW, decision.result());
                store.event(r.projectId(), r.id(), v.id(), decision.action(), Status.IN_REVIEW, decision.result(), note, json(details));
            }
        }
        audit.record("REVIEW_" + decision.result().name(), "resource", r.id(), Map.of("kind", r.kind().name(), "version", v.versionNo()));
        store.touch(r.id());
        return version(versionId);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Marks every version of everything built on this resource Outdated, with a record for each. */
    private void outdateDependents(ResourceRow r, VersionRow approved) {
        for (UUID dependent : store.dependents(r.id())) {
            for (String[] changed : store.outdate(dependent)) {
                store.event(r.projectId(), dependent, UUID.fromString(changed[0]), "OUTDATED", Status.valueOf(changed[1]), Status.OUTDATED,
                        r.kind().label() + " version " + approved.versionNo() + " is being changed", null);
            }
        }
    }

    private void archiveApproved(ResourceRow r, String why) {
        store.approved(r.id()).ifPresent(old -> {
            moveOrConflict(old, Status.APPROVED, Status.ARCHIVED);
            store.event(r.projectId(), r.id(), old.id(), "ARCHIVED", Status.APPROVED, Status.ARCHIVED, why, null);
        });
    }

    private void requireChecks(ResourceRow r, JsonNode content) {
        List<Check> checks = checks(r, content);
        if (!Review.passes(checks)) throw conflict("It can't be approved yet: " + failing(checks));
    }

    List<Check> checks(ResourceRow r, JsonNode content) {
        Context ctx = new Context(r.projectId(), r.id(),
                k -> approved(r.projectId(), k).map(VersionRow::id).orElse(null),
                k -> approved(r.projectId(), k).map(v -> readJson(v.content())).orElse(null),
                k -> store.approvedOfKind(r.projectId(), k),
                () -> store.newestOfKind(r.projectId(), r.kind()).stream()
                        .filter(v -> !v.resourceId().equals(r.id())).map(v -> readJson(v.content())).toList());
        List<Check> out = new java.util.ArrayList<>(handler(r.kind()).checks(content, ctx));
        String hash = Review.hash(Review.canonical(content));
        for (Review.Gate g : gates) out.addAll(g.checks(r.projectId(), r.id(), r.kind(), hash));
        return out;
    }

    private VersionDetail detail(VersionRow v, ResourceRow r, boolean latest) {
        VersionRow full = v.content() != null ? v : store.version(v.id()).orElseThrow();
        JsonNode content = readJson(full.content());
        UUID me = current.require().getId();
        boolean canEdit = projects.canEdit(r.projectId());
        boolean canReview = projects.canReview(r.projectId());
        boolean mustDiffer = store.reviewerMustDiffer(r.projectId());
        boolean author = me.equals(full.createdBy());
        You you = new You(canEdit, canReview, author, canReview && !(mustDiffer && author), mustDiffer);
        return new VersionDetail(VersionSummary.of(full), content, checks(r, content), latest, you);
    }

    private boolean isLatest(VersionRow v) {
        List<VersionRow> all = store.versions(v.resourceId());
        return !all.isEmpty() && all.get(0).id().equals(v.id());
    }

    private void requireLatest(VersionRow v) {
        if (!isLatest(v)) throw conflict("Version " + v.versionNo() + " isn't the newest version any more.");
    }

    private void requireEdit(ResourceRow r) {
        projects.requireAccess(r.projectId(), true, EDIT_MESSAGE);
    }

    private void moveOrConflict(VersionRow v, Status from, Status to) {
        if (store.move(v.id(), from, to) == 0) throw conflict("Version " + v.versionNo() + " changed meanwhile. Reload and try again.");
    }

    private ContentHandler handler(Kind kind) {
        ContentHandler h = handlers.get(kind);
        if (h == null) throw new IllegalStateException("No handler for " + kind);
        return h;
    }

    private ResourceRow findResource(UUID id) {
        return store.resource(id).orElseThrow(() -> ApiException.notFound("Resource"));
    }

    private VersionRow findVersion(UUID id) {
        return store.version(id).orElseThrow(() -> ApiException.notFound("Version"));
    }

    private static ResourceInfo info(ResourceRow r) {
        return new ResourceInfo(r.id(), r.projectId(), r.kind(), r.title(), r.topicId(), r.level(), r.createdAt(), r.updatedAt());
    }

    private static String clip(String s, int max) {
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    static String failing(List<Check> checks) {
        return checks.stream().filter(c -> c.blocking() && !c.ok()).map(Check::message).collect(Collectors.joining("; "));
    }

    static String label(Status s) {
        return switch (s) {
            case DRAFT -> "a draft";
            case IN_REVIEW -> "in review";
            case CHANGES_REQUESTED -> "sent back for changes";
            case APPROVED -> "approved";
            case REJECTED -> "rejected";
            case OUTDATED -> "outdated";
            case ARCHIVED -> "archived";
        };
    }

    private static ApiException handMade(ResourceRow r) {
        return new ApiException(ErrorCode.CONFLICT, "A " + r.kind().label() + " is made by Leenax from the QA results, not edited by hand. Make it again instead.");
    }

    private static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static JsonNode readJson(String s) {
        try {
            return s == null ? JSON.createObjectNode() : JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("Stored content isn't JSON", e);
        }
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(Objects.requireNonNull(o));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
