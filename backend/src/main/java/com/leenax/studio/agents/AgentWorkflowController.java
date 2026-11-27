package com.leenax.studio.agents;

import com.leenax.studio.agents.WorkflowDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent-workflows")
public class AgentWorkflowController {
    private final AgentWorkflowService workflows;
    public AgentWorkflowController(AgentWorkflowService workflows) { this.workflows = workflows; }

    @GetMapping
    public WorkflowPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                             @RequestParam(required = false) UUID projectId, @RequestParam(required = false) String search) {
        return workflows.list(page, size, projectId, search);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowResponse create(@Valid @RequestBody WorkflowRequest request) { return workflows.create(request); }
    @GetMapping("/{id}")
    public WorkflowResponse get(@PathVariable UUID id) { return workflows.get(id); }
    @PutMapping("/{id}")
    public WorkflowResponse update(@PathVariable UUID id, @Valid @RequestBody WorkflowRequest request) { return workflows.update(id, request); }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { workflows.delete(id); }

    @PostMapping("/{id}/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WorkflowExecution execute(@PathVariable UUID id, @Valid @RequestBody WorkflowExecutionRequest request) { return workflows.execute(id, request.input()); }
    @GetMapping("/{id}/executions")
    public WorkflowExecutionPage executions(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String status) {
        workflows.get(id);
        return workflows.executions(page, size, id, null, status);
    }
}
