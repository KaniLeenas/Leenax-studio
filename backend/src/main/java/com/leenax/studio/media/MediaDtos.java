package com.leenax.studio.media;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class MediaDtos {

    private MediaDtos() {
    }

    public record MediaResponse(
            UUID id,
            UUID projectId,
            MediaKind kind,
            MediaSource source,
            String filename,
            String contentType,
            long sizeBytes,
            Integer width,
            Integer height,
            Long durationMs,
            Instant createdAt,
            String url,        // signed, short-lived
            String posterUrl) { // signed, short-lived (videos and images)
    }

    /** Partial update. To move media out of a project, send "unassign": true. */
    public record UpdateMediaRequest(
            @Size(min = 1, max = 255) String filename,
            UUID projectId,
            Boolean unassign) {
    }

    public record DownloadUrlResponse(String url, Instant expiresAt) {
    }
}
