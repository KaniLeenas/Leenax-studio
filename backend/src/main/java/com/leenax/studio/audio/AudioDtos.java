package com.leenax.studio.audio;

import com.leenax.studio.media.MediaDtos.MediaResponse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class AudioDtos {

    private AudioDtos() {
    }

    public record CleanupRequest(@NotNull UUID mediaId, @NotEmpty List<AudioRecipes.Operation> operations) {
    }

    public record SpeedPitchRequest(@NotNull UUID mediaId,
                                    @DecimalMin("0.5") @DecimalMax("2.0") double speed,
                                    @DecimalMin("-12") @DecimalMax("12") double semitones) {
    }

    /**
     * @param musicVolumeDb music level before ducking, e.g. -18
     * @param duck          lower the music automatically while someone speaks
     */
    public record MixRequest(@NotNull UUID voiceMediaId, @NotNull UUID musicMediaId,
                             @DecimalMin("-40") @DecimalMax("6") double musicVolumeDb,
                             boolean duck,
                             @DecimalMin("0") @DecimalMax("10") double fadeSeconds,
                             @Size(max = 160) String title) {
    }

    public record ExtractRequest(@NotNull UUID videoMediaId) {
    }

    public record SoundEffectRequest(@NotNull UUID accountId,
                                     @NotBlank @Size(max = 450) String prompt,
                                     @DecimalMin("0.5") @DecimalMax("30") Double durationSeconds,
                                     boolean loop,
                                     UUID projectId) {
    }

    public record AudioResult(MediaResponse media) {
    }
}
