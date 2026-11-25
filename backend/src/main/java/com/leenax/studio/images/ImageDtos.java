package com.leenax.studio.images;

import com.leenax.studio.media.MediaDtos.MediaResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class ImageDtos {

    private ImageDtos() {
    }

    /**
     * @param size        OpenAI only: "WIDTHxHEIGHT", e.g. "1280x720"
     * @param aspectRatio Gemini only: "16:9", "1:1", "9:16" …
     * @param imageSize   Gemini only: "1K", "2K" or "4K"
     * @param references  up to 4 images from the media library to guide or edit
     */
    public record GenerateRequest(@NotNull UUID accountId,
                                  @NotBlank @Size(max = 120) String model,
                                  @NotBlank @Size(max = 4000) String prompt,
                                  @Pattern(regexp = "\\d{2,4}x\\d{2,4}") String size,
                                  @Pattern(regexp = "\\d{1,2}:\\d{1,2}") String aspectRatio,
                                  @Pattern(regexp = "1K|2K|4K") String imageSize,
                                  @Min(1) @Max(4) Integer count,
                                  @Pattern(regexp = "low|medium|high|auto") String quality,
                                  boolean transparent,
                                  @Size(max = 4) List<UUID> references,
                                  UUID projectId) {
    }

    public record ResizeRequest(@NotNull UUID mediaId, @Min(64) @Max(4096) int width, @Min(64) @Max(4096) int height,
                                @NotNull ImageRecipes.Fit fit, @Pattern(regexp = "#[0-9a-fA-F]{6}") String background,
                                @Pattern(regexp = "png|jpg") String format) {
    }

    public record RemoveBackgroundRequest(@NotNull UUID mediaId) {
    }

    public record ImagesResult(List<MediaResponse> images) {
    }

    public record ToolsStatus(boolean backgroundRemoval, List<ImageRecipes.Preset> presets) {
    }
}
