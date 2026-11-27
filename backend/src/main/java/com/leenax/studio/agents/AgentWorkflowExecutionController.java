package com.leenax.studio.agents;

import com.leenax.studio.agents.WorkflowDtos.WorkflowExecution;
import com.leenax.studio.agents.WorkflowDtos.WorkflowExecutionPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent-workflow-executions")
public class AgentWorkflowExecutionController {
    private final AgentWorkflowService workflows;
    public AgentWorkflowExecutionController(AgentWorkflowService workflows) { this.workflows = workflows; }

    @GetMapping
    public WorkflowExecutionPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) UUID workflowId, @RequestParam(required = false) UUID projectId,
                                      @RequestParam(required = false) String status) {
        return workflows.executions(page, size, workflowId, projectId, status);
    }
    @GetMapping("/{id}")
    public WorkflowExecution get(@PathVariable UUID id) { return workflows.execution(id); }
    @PostMapping("/{id}/approve")
    public WorkflowExecution approve(@PathVariable UUID id) { return workflows.approve(id, true); }
    @PostMapping("/{id}/reject")
    public WorkflowExecution reject(@PathVariable UUID id) { return workflows.approve(id, false); }
    @PostMapping("/{id}/pause")
    public WorkflowExecution pause(@PathVariable UUID id) { return workflows.pause(id); }
    @PostMapping("/{id}/resume")
    public WorkflowExecution resume(@PathVariable UUID id) { return workflows.resume(id); }
    @PostMapping("/{id}/cancel")
    public WorkflowExecution cancel(@PathVariable UUID id) { return workflows.cancel(id); }
    @PostMapping("/{id}/retry")
    public WorkflowExecution retry(@PathVariable UUID id) { return workflows.retry(id); }
}
