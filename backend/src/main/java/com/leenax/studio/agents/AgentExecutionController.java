package com.leenax.studio.agents;

import com.leenax.studio.agents.AgentDtos.ExecutionLog;
import com.leenax.studio.agents.AgentDtos.ExecutionPage;
import com.leenax.studio.agents.AgentDtos.ExecutionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent-executions")
public class AgentExecutionController {
    private final AgentExecutionService executions;
    public AgentExecutionController(AgentExecutionService executions) { this.executions = executions; }

    @GetMapping
    public ExecutionPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                              @RequestParam(required = false) UUID projectId, @RequestParam(required = false) String status,
                              @RequestParam(required = false) UUID agentId) {
        return executions.list(page, size, projectId, status, agentId);
    }

    @GetMapping("/{id}")
    public ExecutionResponse get(@PathVariable UUID id) { return executions.get(id); }

    @GetMapping("/{id}/logs")
    public List<ExecutionLog> logs(@PathVariable UUID id) { return executions.logs(id); }

    @PostMapping("/{id}/retry")
    public ExecutionResponse retry(@PathVariable UUID id) { return executions.retry(id); }

    @PostMapping("/{id}/cancel")
    public ExecutionResponse cancel(@PathVariable UUID id) { return executions.cancel(id); }
}
