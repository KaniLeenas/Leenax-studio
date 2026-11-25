package com.leenax.studio.voice;

import com.leenax.studio.ai.provider.ProviderCode;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VoiceDtos {

    private VoiceDtos() {
    }

    /** The exact sentence typed to confirm a voice clone (also shown in the UI). */
    public static final String CONSENT_STATEMENT = Consent.STATEMENT;

    public record VoiceOption(String id, String name, String category, String gender, String language, String accent,
                              String description, String previewUrl, boolean cloned) {
    }

    /**
     * How a voice should sound. Every field is optional; a profile's saved values fill the gaps.
     *
     * @param pitch semitones, -12..12 (applied with FFmpeg)
     * @param speed 0.5..2.0
     */
    public record VoiceStyle(@Pattern(regexp = "(?i)NEUTRAL|HAPPY|SAD|ANGRY|EXCITED|STORYTELLING|DOCUMENTARY|PROFESSIONAL") String emotion,
                             @Size(max = 300) String direction,
                             @DecimalMin("0.5") @DecimalMax("2.0") Double speed,
                             @DecimalMin("-12") @DecimalMax("12") Double pitch,
                             @DecimalMin("0") @DecimalMax("1") Double stability,
                             @DecimalMin("0") @DecimalMax("1") Double similarity) {
    }

    /** Either a saved profile, or an account plus a provider voice id. */
    public record VoiceChoice(UUID profileId, UUID accountId, @Size(max = 120) String voiceId, @Size(max = 120) String model) {
    }

    public record NarrationRequest(@NotNull @Valid VoiceChoice voice,
                                   @NotBlank @Size(max = 50_000) String text,
                                   @Pattern(regexp = "EN|TA") String language,
                                   @Valid VoiceStyle style,
                                   @Size(max = 160) String title,
                                   UUID projectId) {
    }

    public record PreviewRequest(@NotNull @Valid VoiceChoice voice, @NotBlank @Size(max = 300) String text,
                                 @Pattern(regexp = "EN|TA") String language, @Valid VoiceStyle style) {
    }

    public record Speaker(@NotBlank @Size(max = 40) String name, @NotNull UUID profileId) {
    }

    public record DialogueRequest(@NotBlank @Size(max = 30_000) String script,
                                  @NotEmpty @Size(max = 6) List<@Valid Speaker> speakers,
                                  @Pattern(regexp = "EN|TA") String language,
                                  @DecimalMin("0") @DecimalMax("3") Double gapSeconds,
                                  @Size(max = 160) String title,
                                  UUID projectId) {
    }

    public record ProfileRequest(@NotNull UUID accountId, @NotBlank @Size(max = 120) String voiceId,
                                 @NotBlank @Size(max = 80) String name, @Pattern(regexp = "EN|TA") String language,
                                 @Size(max = 120) String model, @Valid VoiceStyle style) {
    }

    public record ProfileUpdate(@Size(max = 80) String name, @Pattern(regexp = "EN|TA") String language,
                                @Size(max = 120) String model, @Valid VoiceStyle style, Boolean shared) {
    }

    public record CloneRequest(@NotNull UUID accountId,
                               @NotBlank @Size(max = 80) String name,
                               @Size(max = 300) String description,
                               @Pattern(regexp = "EN|TA") String language,
                               @NotEmpty @Size(max = 25) List<UUID> sampleMediaIds,
                               boolean consentOwnVoice,
                               @NotBlank @Size(max = 300) String consentStatement,
                               boolean removeBackgroundNoise) {
    }

    public record ProfileResponse(UUID id, UUID accountId, String accountName, ProviderCode provider, String providerVoiceId,
                                  String name, VoiceProfile.Kind kind, String language, String model,
                                  Map<String, Object> settings, Map<String, Object> consent,
                                  List<String> sampleMediaIds, boolean shared, UUID createdBy, Instant createdAt) {
    }

    public record RuleRequest(@NotBlank @Size(max = 120) String term, @NotBlank @Size(max = 240) String replacement,
                              @Pattern(regexp = "EN|TA") String language) {
    }

    public record RuleResponse(UUID id, String term, String replacement, String language) {
    }

    public record SpeechResult(MediaResponse media, int chunks, int characters) {
    }
}
