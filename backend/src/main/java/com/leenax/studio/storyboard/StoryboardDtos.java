package com.leenax.studio.storyboard;

import com.leenax.studio.ai.generation.GenerationJob;
import com.leenax.studio.ai.provider.VideoRequest;
import com.leenax.studio.autoedit.TimelineBuilder;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.projects.Project;
import com.leenax.studio.scripts.ScriptDtos.Writer;
import com.leenax.studio.voice.VoiceDtos.VoiceChoice;
import com.leenax.studio.voice.VoiceDtos.VoiceStyle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StoryboardDtos {

    private StoryboardDtos() {
    }

    public record CreateRequest(UUID projectId, @NotBlank @Size(max = 200) String title, @NotNull TimelineBuilder.Format aspectRatio,
                                @NotNull Project.Language language, @Size(max = 1000) String style) {
    }

    public record FromScriptRequest(@NotNull UUID scriptId, @NotNull @Valid Writer writer, @NotNull TimelineBuilder.Format aspectRatio,
                                    @Size(max = 1000) String style, @Size(max = 200) String title) {
    }

    public record UpdateRequest(@NotBlank @Size(max = 200) String title, @NotNull TimelineBuilder.Format aspectRatio,
                                @NotNull Project.Language language, @Size(max = 1000) String style, UUID projectId) {
    }

    public record DialogueLine(@NotBlank @Size(max = 40) String character, @NotBlank @Size(max = 1000) String line) {
    }

    public record SceneRequest(@Size(max = 160) String title, @Size(max = 4000) String description,
                               @Size(max = 6000) String narration, @Size(max = 40) List<@Valid DialogueLine> dialogue,
                               @DecimalMin("1") @DecimalMax("120") Double durationSeconds,
                               @Size(max = 40) String cameraAngle, @Size(max = 40) String cameraMovement,
                               @Size(max = 300) String lighting, @Size(max = 300) String composition,
                               @Size(max = 2000) String soundNotes, @Size(max = 8) List<UUID> characterIds,
                               @Size(max = 4000) String imagePrompt, @Size(max = 4000) String videoPrompt,
                               @Size(max = 4) List<UUID> referenceMediaIds, UUID imageMediaId, UUID videoMediaId) {
    }

    public record ReorderRequest(@NotEmpty @Size(max = 200) List<UUID> sceneIds) {
    }

    public record SceneImageRequest(@NotNull UUID accountId, @NotBlank @Size(max = 120) String model) {
    }

    public record SceneVideoRequest(@NotNull UUID accountId, @NotNull UUID modelId, @NotNull VideoRequest.Mode mode,
                                    @Min(1) @Max(60) Integer durationSeconds, @Size(max = 20) String resolution, Boolean audio) {
    }

    public record NarrateRequest(@NotNull @Valid VoiceChoice voice, @Valid VoiceStyle style) {
    }

    public record ExportRequest(@Size(max = 160) String name, boolean captions, UUID musicMediaId,
                                @DecimalMin("0") @DecimalMax("1") Double musicVolume) {
    }

    public record VideoJob(UUID id, GenerationJob.Status status, Double progress, String errorMessage, String provider,
                           String model) {
    }

    public record SceneResponse(UUID id, int position, String title, String description, String narration,
                                List<Map<String, Object>> dialogue, double durationSeconds, String cameraAngle,
                                String cameraMovement, String lighting, String composition, String soundNotes,
                                List<UUID> characterIds, String imagePrompt, String videoPrompt,
                                String suggestedImagePrompt, String suggestedVideoPrompt,
                                MediaResponse image, MediaResponse video, MediaResponse dialogueAudio, VideoJob videoJob,
                                List<MediaResponse> references) {
    }

    public record StoryboardResponse(UUID id, UUID projectId, UUID scriptId, String title, TimelineBuilder.Format aspectRatio,
                                     Project.Language language, String style, MediaResponse narration,
                                     List<Map<String, Object>> narrationParts, UUID timelineId, double totalSeconds,
                                     List<SceneResponse> scenes, Instant createdAt, Instant updatedAt) {
    }

    public record StoryboardSummary(UUID id, UUID projectId, UUID scriptId, String title, TimelineBuilder.Format aspectRatio,
                                    Project.Language language, int sceneCount, int imagesReady, int videosReady,
                                    double totalSeconds, MediaResponse cover, UUID timelineId, Instant updatedAt) {
    }

    public record ExportResponse(UUID timelineId, double totalSeconds, List<String> notes) {
    }
}
