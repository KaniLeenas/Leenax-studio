package com.leenax.studio.subtitles;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SubtitleDtos {

    private SubtitleDtos() {
    }

    /**
     * @param language     spoken language as ISO 639-1 ("ta", "en"), or blank to detect automatically
     * @param maxLineChars characters per subtitle line (16–60); 42 suits landscape video, ~28 suits Shorts and Reels
     */
    public record TranscribeRequest(@NotNull UUID mediaId, @NotNull UUID accountId,
                                    @Pattern(regexp = "[a-z]{2}") String language,
                                    @Min(16) @Max(60) Integer maxLineChars) {
    }

    public record TranslateRequest(@NotNull UUID accountId, @NotBlank @Size(max = 120) String model,
                                   @NotBlank @Pattern(regexp = "[a-z]{2}") String targetLanguage) {
    }

    public record CueDto(@DecimalMin("0") double start, @DecimalMin("0") double end, @NotNull @Size(max = 500) String text) {
    }

    public record CreateTrackRequest(@NotNull UUID mediaId, @NotBlank @Pattern(regexp = "[a-z]{2}") String language,
                                     @Size(max = 120) String label, @Size(max = 5000) List<@Valid CueDto> cues) {
    }

    public record UpdateTrackRequest(@Size(max = 120) String label, @Pattern(regexp = "[a-z]{2}") String language,
                                     @Size(max = 5000) List<@Valid CueDto> cues) {
    }

    public record TrackSummary(UUID id, UUID mediaId, String language, String label, SubtitleTrack.Source source,
                               int cueCount, Instant updatedAt) {
    }

    public record TrackResponse(UUID id, UUID mediaId, UUID projectId, String language, String label,
                                SubtitleTrack.Source source, List<Cue> cues, Instant createdAt, Instant updatedAt) {
    }
}
