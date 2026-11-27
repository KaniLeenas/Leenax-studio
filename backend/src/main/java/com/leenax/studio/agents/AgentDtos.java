package com.leenax.studio.agents;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AgentDtos {
    private AgentDtos() {}

    public record AgentRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 40) String category,
            @NotBlank @Size(max = 12000) String instructions,
            @NotNull UUID accountId,
            @NotBlank @Size(max = 120) String model,
            @Min(0) @Max(2) double temperature,
            @Min(1) @Max(32000) int maxOutputTokens,
            JsonNode inputSchema,
            JsonNode outputSchema,
            @Size(max = 20) List<@Size(max = 80) String> tools,
            @Size(max = 20) List<@Size(max = 40) String> permissions,
            @Min(5) @Max(900) int timeoutSeconds,
            @Min(0) @Max(5) int retryCount,
            UUID projectId,
            Boolean enabled) {}

    public record AgentResponse(UUID id, String name, String description, String category, String instructions,
                                UUID accountId, String model, double temperature, int maxOutputTokens,
                                JsonNode inputSchema, JsonNode outputSchema, List<String> tools,
                                List<String> permissions, int timeoutSeconds, int retryCount, UUID projectId,
                                UUID createdBy, boolean enabled, int version, Instant createdAt, Instant updatedAt) {}

    public record AgentSummary(UUID id, String name, String category, UUID projectId, boolean enabled,
                               String model, int version, Instant updatedAt) {}
    public record AgentPage(int page, int size, long total, List<AgentSummary> items) {}
    public record AgentTemplate(String key, String name, String description, String category, JsonNode configuration) {}
    public record AgentTool(UUID id, String key, String name, String description, JsonNode inputSchema,
                            JsonNode outputSchema, String permission, int timeoutSeconds, boolean enabled) {}
    public record AgentDashboard(long totalAgents, long activeAgents, long disabledAgents, long runningExecutions,
                                 long failedExecutions, long completedExecutions, long queuedExecutions) {}
    public record AgentPolicy(boolean allowPaidModels) {}

    public record ExecuteRequest(@NotNull JsonNode input) {}
    public record ExecutionResponse(UUID id, UUID agentId, String agentName, UUID projectId, UUID createdBy,
                                    String status, String provider, String model, JsonNode input, JsonNode output,
                                    String error, Integer inputTokens, Integer outputTokens, Double estimatedCost,
                                    Instant startedAt, Instant completedAt, Instant createdAt, UUID retryOf) {}
    public record ExecutionPage(int page, int size, long total, List<ExecutionResponse> items) {}
    public record ExecutionLog(long id, String level, String stage, String message, JsonNode details, Instant createdAt) {}
    public record AgentVersion(int version, JsonNode configuration, UUID changedBy, Instant createdAt) {}
}
