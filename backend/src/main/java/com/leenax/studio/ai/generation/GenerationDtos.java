package com.leenax.studio.ai.generation;

import com.leenax.studio.ai.provider.ProviderCode;
import com.leenax.studio.ai.provider.VideoRequest;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GenerationDtos {

    private GenerationDtos() {
    }

    public record VideoGenerationRequest(
            @NotNull UUID accountId,
            @NotNull UUID modelId,
            @NotNull VideoRequest.Mode mode,
            @NotBlank @Size(max = 4000) String prompt,
            @Size(max = 1000) String negativePrompt,
            @Min(1) @Max(60) Integer durationSeconds,
            @Size(max = 20) String aspectRatio,
            @Size(max = 20) String resolution,
            Boolean audio,
            UUID imageMediaId,
            UUID projectId,
            @Min(1) @Max(4) Integer variations) {

        public int variationCount() {
            return variations == null ? 1 : variations;
        }
    }

    public record EstimateResponse(BigDecimal perVideo, BigDecimal total, String unit, int variations,
                                   BigDecimal balance, String balanceUnit, BigDecimal balanceAfter,
                                   boolean lowCredit, boolean insufficient, List<String> warnings) {
    }

    public record JobResponse(UUID id, UUID groupId, int variationIndex, UUID accountId, String accountName,
                              ProviderCode provider, String providerName, UUID modelId, String modelCode,
                              String modelName, VideoRequest.Mode mode, String prompt, String negativePrompt,
                              Integer durationSeconds, String aspectRatio, String resolution, Boolean audio,
                              UUID inputMediaId, UUID projectId, GenerationJob.Status status, Double progress,
                              int attempts, String errorCode, String errorMessage, BigDecimal estimatedCost,
                              BigDecimal actualCost, String costUnit, MediaResponse output, Instant createdAt,
                              Instant submittedAt, Instant completedAt) {
    }

    public record CreateResponse(List<JobResponse> jobs, EstimateResponse estimate) {
    }

    public record EnhanceRequest(@NotNull UUID accountId, @NotBlank @Size(max = 120) String model,
                                 @NotBlank @Size(max = 4000) String prompt, String language,
                                 @Size(max = 160) String targetModel) {
    }

    public record EnhanceResponse(String prompt) {
    }
}
