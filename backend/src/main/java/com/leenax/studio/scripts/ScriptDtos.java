package com.leenax.studio.scripts;

import com.leenax.studio.projects.Project;
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

public final class ScriptDtos {

    private ScriptDtos() {
    }

    /** The text model that writes: any OpenAI, Anthropic or Gemini account and a model it offers. */
    public record Writer(@NotNull UUID accountId, @NotBlank @Size(max = 120) String model) {
    }

    public record IdeasRequest(@NotNull Writer writer, @NotBlank @Size(max = 500) String topic, @NotNull String videoType,
                               @NotNull Project.Language language, @Min(1) @Max(10) Integer count,
                               @Size(max = 300) String audience) {
    }

    public record IdeasResponse(List<ScriptWriting.Idea> ideas) {
    }

    public record WriteRequest(@NotNull Writer writer, UUID projectId, @Size(max = 200) String title,
                               @NotBlank @Size(max = 2000) String idea, @NotNull String videoType,
                               @NotNull Project.Language language, @Min(1) @Max(60) Integer minutes,
                               @Size(max = 200) String tone, @Size(max = 2000) String notes) {
    }

    public record CreateRequest(UUID projectId, @NotBlank @Size(max = 200) String title, @NotNull String videoType,
                                @NotNull Project.Language language, @Size(max = 2000) String idea,
                                @Size(max = 100_000) String body) {
    }

    public record UpdateRequest(@NotBlank @Size(max = 200) String title, @Size(max = 2000) String idea,
                                @NotNull @Size(max = 100_000) String body, @NotNull Project.Language language,
                                @NotNull String videoType, UUID projectId) {
    }

    public record RewriteRequest(@NotNull Writer writer, @NotBlank @Size(max = 1000) String instruction) {
    }

    public record MetadataRequest(@NotNull Writer writer, @NotEmpty @Size(max = 10) List<String> platforms) {
    }

    public record ScriptResponse(UUID id, UUID projectId, UUID parentId, String title, Project.Language language,
                                 String videoType, String idea, String body, Map<String, Object> sections,
                                 Map<String, Object> metadata, int versionNo, int wordCount, double spokenMinutes,
                                 Instant createdAt, Instant updatedAt) {
    }
}
