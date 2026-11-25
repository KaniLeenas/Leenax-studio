package com.leenax.studio.ai.generation;

import com.leenax.studio.ai.generation.GenerationDtos.JobResponse;
import com.leenax.studio.media.MediaService;
import org.springframework.stereotype.Component;

@Component
public class JobMapper {

    private final MediaService media;

    public JobMapper(MediaService media) {
        this.media = media;
    }

    public JobResponse toResponse(GenerationJob j) {
        return new JobResponse(j.getId(), j.getGroupId(), j.getVariationIndex(), j.getAccountId(), j.getAccountName(),
                j.getProvider(), j.getProvider().displayName(), j.getModelId(), j.getModelCode(), j.getModelName(),
                j.getMode(), j.getPrompt(), j.getNegativePrompt(), j.durationSeconds(), j.aspectRatio(), j.resolution(),
                j.audio(), j.getInputMediaId(), j.getProjectId(), j.getStatus(),
                j.getProgress() == null ? null : j.getProgress().doubleValue(), j.getAttempts(), j.getErrorCode(),
                j.getErrorMessage(), j.getEstimatedCost(), j.getActualCost(), j.getCostUnit(),
                j.getStatus() == GenerationJob.Status.COMPLETED ? media.findResponse(j.getOutputMediaId()).orElse(null) : null,
                j.getCreatedAt(), j.getSubmittedAt(), j.getCompletedAt());
    }
}
