package com.leenax.studio.thumbnails;

import com.leenax.studio.media.MediaDtos.MediaResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class ThumbnailDtos {

    private ThumbnailDtos() {
    }

    public record DesignRequest(@NotBlank @Size(max = 160) String name,
                                @Min(64) @Max(4096) int width, @Min(64) @Max(4096) int height,
                                @NotNull Map<String, Object> document, boolean template, UUID projectId) {
    }

    public record DesignUpdate(@Size(max = 160) String name, @Min(64) @Max(4096) Integer width, @Min(64) @Max(4096) Integer height,
                               Map<String, Object> document, Boolean template, UUID projectId) {
    }

    public record DuplicateRequest(@Size(max = 160) String name, Boolean template) {
    }

    public record DesignSummary(UUID id, String name, int width, int height, boolean template, UUID projectId,
                                MediaResponse export, Instant updatedAt) {
    }

    public record DesignResponse(UUID id, String name, int width, int height, Map<String, Object> document,
                                 boolean template, UUID projectId, MediaResponse export, Instant createdAt, Instant updatedAt) {
    }
}
