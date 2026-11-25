package com.leenax.studio.approvals;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Decision;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.Review.Origin;
import com.leenax.studio.approvals.Review.Status;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record VersionSummary(UUID id, UUID resourceId, int versionNo, Status status, Origin origin, String contentHash,
                                 UUID basedOn, UUID dependsOn, UUID createdById, String createdBy, Instant createdAt,
                                 Instant updatedAt) {

        static VersionSummary of(ReviewStore.VersionRow v) {
            return new VersionSummary(v.id(), v.resourceId(), v.versionNo(), v.status(), v.origin(), v.contentHash(), v.basedOn(),
                    v.dependsOn(), v.createdBy(), v.createdByName(), v.createdAt(), v.updatedAt());
        }
    }

    /**
     * What the person asking may do with this version.
     * mayDecide: a reviewing role, and not blocked by "reviewer must not be the author".
     */
    public record You(boolean canEdit, boolean canReview, boolean isAuthor, boolean mayDecide, boolean reviewerMustDiffer) {
    }

    public record VersionDetail(VersionSummary version, JsonNode content, List<Check> checks, boolean latest, You you) {
    }

    public record ResourceInfo(UUID id, UUID projectId, Kind kind, String title, String topicId, String level, Instant createdAt,
                               Instant updatedAt) {
    }

    /** A project's syllabus or topic list: every version, the newest in full, and the approved one. */
    public record ResourceState(Kind kind, ResourceInfo resource, List<VersionSummary> versions, VersionDetail latest,
                                UUID approvedVersionId, Integer approvedVersionNo, boolean canEdit, boolean canReview) {
    }

    public record EventResponse(UUID id, UUID versionId, int versionNo, String action, String previousStatus, String newStatus,
                                String comment, JsonNode details, UUID actorId, String actor, Instant createdAt) {
    }

    public record ContentRequest(@NotNull JsonNode content) {
    }

    /** Edit: a new draft from {@code fromVersionId}, with new content or a copy of that version's. */
    public record EditRequest(@NotNull UUID fromVersionId, JsonNode content) {
    }

    public record CommentRequest(@Size(max = 2000) String comment) {
    }

    /** content is required for APPROVE_WITH_EDITS: the reviewer's edited version. */
    public record DecisionRequest(@NotNull Decision decision, @Size(max = 2000) String comment, JsonNode content) {
    }
}
