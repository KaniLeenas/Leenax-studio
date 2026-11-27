package com.leenax.studio.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.access.Actor;
import com.leenax.studio.access.ActorContext;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/agents")
public class AgentInternalController {
    public record StartRequest(UUID userId, UUID projectId, UUID workflowExecutionId, JsonNode input) {}
    public record Started(UUID executionId, int timeoutSeconds) {}

    private final String serviceToken;
    private final AgentService agents;
    private final AgentExecutionService executions;
    private final AgentWorkflowService workflows;

    public AgentInternalController(@Value("${leenax.agents.service-token:}") String serviceToken, AgentService agents,
                                   AgentExecutionService executions, AgentWorkflowService workflows) {
        this.serviceToken = serviceToken; this.agents = agents; this.executions = executions; this.workflows = workflows;
    }

    @PostMapping("/workflows/{workflowExecutionId}/run/{agentId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Started start(@RequestHeader(name = "X-Leenax-Agent-Service", required = false) String token,
                        @PathVariable UUID workflowExecutionId, @PathVariable UUID agentId, @RequestBody StartRequest request) {
        requireService(token);
        if (!workflowExecutionId.equals(request.workflowExecutionId()) || request.userId() == null) throw ApiException.badRequest("Invalid workflow execution context.");
        workflows.requireInternalActive(workflowExecutionId, request.userId(), request.projectId());
        return ActorContext.call(Actor.worker(request.userId()), () -> {
            AgentDtos.AgentResponse agent = agents.getForExecution(agentId);
            if (agent == null) throw ApiException.notFound("Agent");
            agents.requireCanExecuteForUser(agent, request.userId(), request.projectId());
            AgentDtos.ExecutionResponse run = executions.enqueueForWorkflow(agentId, request.input(), request.userId(), request.projectId(), workflowExecutionId);
            return new Started(run.id(), agent.timeoutSeconds());
        });
    }

    @GetMapping("/workflows/{workflowExecutionId}/runs/{executionId}")
    public AgentDtos.ExecutionResponse status(@RequestHeader(name = "X-Leenax-Agent-Service", required = false) String token,
                                              @PathVariable UUID workflowExecutionId, @PathVariable UUID executionId,
                                              @RequestParam UUID userId, @RequestParam(required = false) UUID projectId) {
        requireService(token);
        workflows.requireInternalActive(workflowExecutionId, userId, projectId);
        return executions.getForWorkflow(executionId, workflowExecutionId, userId);
    }

    private void requireService(String supplied) {
        if (serviceToken == null || serviceToken.isBlank() || supplied == null
                || !MessageDigest.isEqual(serviceToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Internal agent service authentication failed.");
        }
    }
}
