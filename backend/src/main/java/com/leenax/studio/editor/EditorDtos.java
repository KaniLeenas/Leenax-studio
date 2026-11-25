package com.leenax.studio.editor;

import com.leenax.studio.media.MediaDtos.MediaResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class EditorDtos {

    private EditorDtos() {
    }

    public record CreateTimelineRequest(@NotBlank @Size(max = 160) String name, @NotNull Map<String, Object> document, UUID projectId) {
    }

    public record UpdateTimelineRequest(@Size(max = 160) String name, UUID projectId, Boolean unassign) {
    }

    /** Autosave: send the revision you loaded; a newer revision on the server means another tab saved meanwhile. */
    public record SaveDocumentRequest(@Min(0) int revision, @NotNull Map<String, Object> document) {
    }

    public record SaveResult(int revision, BigDecimal durationSeconds, Instant updatedAt) {
    }

    public record TimelineSummary(UUID id, String name, int width, int height, int fps, BigDecimal durationSeconds, UUID projectId,
                                  MediaResponse poster, Instant updatedAt, Instant createdAt) {
    }

    public record TimelineResponse(UUID id, String name, int width, int height, int fps, BigDecimal durationSeconds, UUID projectId,
                                   Map<String, Object> document, int revision, Map<UUID, MediaResponse> media,
                                   Instant updatedAt, Instant createdAt) {
    }

    public record RenderRequest(@NotNull @Min(720) @Max(2160) Integer resolution, @NotNull RenderSettings.Quality quality) {
    }

    public record RenderJobResponse(UUID id, UUID timelineId, String timelineName, UUID projectId, RenderJob.Status status,
                                    double progress, int resolution, RenderSettings.Quality quality, int outputWidth, int outputHeight,
                                    String errorMessage, MediaResponse output, Instant createdAt, Instant submittedAt, Instant completedAt,
                                    java.util.Map<String, Object> validation) {
    }

    public record RenderStatus(boolean available) {
    }
}
