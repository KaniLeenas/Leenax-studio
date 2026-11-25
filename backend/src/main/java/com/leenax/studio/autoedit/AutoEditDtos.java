package com.leenax.studio.autoedit;

import com.leenax.studio.voice.VoiceDtos.VoiceChoice;
import com.leenax.studio.voice.VoiceDtos.VoiceStyle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AutoEditDtos {

    private AutoEditDtos() {
    }

    /**
     * One request shape for all three modes; each mode uses the fields it needs
     * (see {@link AutoEditService#validate}).
     */
    public record AutoEditRequest(
            @NotNull AutoEditJob.Mode mode,
            @NotBlank @Size(max = 160) String name,
            @NotNull TimelineBuilder.Format format,
            @NotNull @Pattern(regexp = "TA|EN") String language,
            UUID projectId,
            /* footage: b-roll (script), speech clips in order (talking head), one long video (highlights) */
            @Size(max = 50) List<UUID> footageIds,
            /* script-to-video */
            @Size(max = 20_000) String script,
            @Size(max = 1000) String topic,
            @Min(15) @Max(900) Integer targetSeconds,
            @Size(max = 200) String style,
            @Valid VoiceChoice voice,
            @Valid VoiceStyle voiceStyle,
            boolean describeFootage,
            UUID imageAccountId,
            @Size(max = 120) String imageModel,
            /* AI accounts */
            UUID textAccountId,
            @Size(max = 120) String textModel,
            UUID transcriptionAccountId,
            /* talking head */
            boolean removeFillers,
            @DecimalMin("0.2") @DecimalMax("2.0") Double maxPause,
            boolean punchIn,
            /* highlights */
            @Min(1) @Max(5) Integer highlightCount,
            @Min(10) @Max(60) Integer minSeconds,
            @Min(15) @Max(90) Integer maxSeconds,
            /* all modes */
            boolean captions,
            UUID musicMediaId,
            @DecimalMin("0") @DecimalMax("1") Double musicVolume) {
    }

    public record JobResponse(UUID id, AutoEditJob.Mode mode, String name, UUID projectId, AutoEditJob.Status status, String step,
                              double progress, Map<String, Object> settings, Map<String, Object> result, String errorMessage,
                              Instant createdAt, Instant startedAt, Instant completedAt) {
    }
}
