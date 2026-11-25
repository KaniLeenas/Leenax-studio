package com.leenax.studio.publish;

import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.social.SocialPlatform;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PublishDtos {

    private PublishDtos() {
    }

    /** DRAFT keeps it unpublished; SCHEDULE posts at {@code scheduledAt}; NOW posts straight away. */
    public enum Action { DRAFT, SCHEDULE, NOW }

    public record TargetRequest(@NotNull UUID accountId, @NotNull Map<String, Object> settings) {
    }

    public record PostRequest(
            @NotBlank @Size(max = 160) String title,
            @NotNull UUID mediaId,
            UUID thumbnailMediaId,
            @NotNull Action action,
            Instant scheduledAt,
            @NotNull @Size(max = 20) List<@Valid TargetRequest> targets,
            /** The person's confirmation that public targets will be posted publicly (needed to post or schedule them). */
            Boolean confirmPublic) {
    }

    public record RescheduleRequest(@NotNull Instant scheduledAt) {
    }

    public record TargetResponse(UUID id, UUID accountId, SocialPlatform platform, String accountName,
                                 PostTarget.Status status, Map<String, Object> settings, String step,
                                 BigDecimal progress, String remoteUrl, String errorMessage, String warning,
                                 int attempts, Instant nextAttemptAt, Instant publishedAt) {

        static TargetResponse of(PostTarget t) {
            boolean waiting = t.getStatus() == PostTarget.Status.SCHEDULED || PostTarget.Status.RUNNING.contains(t.getStatus());
            return new TargetResponse(t.getId(), t.getAccountId(), t.getPlatform(), t.getAccountName(), t.getStatus(),
                    t.getSettings(), t.getStep(), t.getProgress(), t.getRemoteUrl(), t.getErrorMessage(), t.getWarning(),
                    t.getAttempts(), waiting && t.getAttempts() > 0 ? t.getRunAt() : null, t.getPublishedAt());
        }
    }

    public record PostResponse(UUID id, String title, MediaResponse media, UUID thumbnailMediaId, Post.Status status,
                               Instant scheduledAt, Instant publishedAt, Instant createdAt, Instant updatedAt,
                               long version, List<TargetResponse> targets) {
    }
}
