package com.leenax.studio.agents;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkflowDtos {
    private WorkflowDtos() {}
    public record WorkflowRequest(@NotBlank @Size(max = 160) String name, @Size(max = 1000) String description,
                                  UUID projectId, @NotNull JsonNode definition, Boolean enabled) {}
    public record WorkflowSummary(UUID id, String name, String description, UUID projectId, UUID createdBy,
                                  boolean enabled, int version, Instant updatedAt) {}
    public record WorkflowResponse(UUID id, String name, String description, UUID projectId, UUID createdBy,
                                   JsonNode definition, boolean enabled, int version, Instant createdAt, Instant updatedAt) {}
    public record WorkflowPage(int page, int size, long total, List<WorkflowSummary> items) {}
    public record WorkflowExecutionRequest(@NotNull JsonNode input) {}
    public record WorkflowExecution(UUID id, UUID workflowId, String workflowName, UUID projectId, UUID createdBy,
                                    String status, String currentNode, JsonNode input, JsonNode output, String error,
                                    Instant startedAt, Instant completedAt, Instant createdAt) {}
    public record WorkflowExecutionPage(int page, int size, long total, List<WorkflowExecution> items) {}
    public record GraphRequest(UUID workflowExecutionId, UUID workflowId, String workflowName, UUID userId, UUID projectId,
                               String threadId, JsonNode definition, JsonNode input, String decision) {}
    public record GraphResponse(String status, String currentNode, JsonNode output, String error) {}
}
