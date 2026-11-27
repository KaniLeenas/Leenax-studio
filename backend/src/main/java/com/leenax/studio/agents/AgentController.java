package com.leenax.studio.agents;

import com.leenax.studio.agents.AgentDtos.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    public record DuplicateRequest(@Size(max = 120) String name) {}
    public record CreateFromTemplateRequest(@NotBlank @Size(max = 120) String name, UUID accountId,
                                           @NotBlank @Size(max = 120) String model, UUID projectId) {}

    private final AgentService agents;
    private final AgentExecutionService executions;

    public AgentController(AgentService agents, AgentExecutionService executions) {
        this.agents = agents; this.executions = executions;
    }

    @GetMapping("/dashboard")
    public AgentDashboard dashboard() { return agents.dashboard(); }

    @GetMapping("/policy")
    public AgentPolicy policy() { return agents.policy(); }

    @GetMapping("/templates")
    public List<AgentTemplate> templates() { return agents.templates(); }

    @GetMapping("/tools")
    public List<AgentTool> tools() { return agents.tools(); }

    @PostMapping("/templates/{key}/create")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentResponse createFromTemplate(@PathVariable String key, @Valid @RequestBody CreateFromTemplateRequest request) {
        AgentTemplate template = agents.templates().stream().filter(t -> t.key().equals(key)).findFirst()
                .orElseThrow(() -> com.leenax.studio.common.ApiException.notFound("Agent template"));
        var config = template.configuration();
        List<String> tools = config.path("tools").isArray() ? java.util.stream.StreamSupport.stream(config.path("tools").spliterator(), false).map(n -> n.asText()).toList() : List.of();
        return agents.create(new AgentDtos.AgentRequest(request.name(), template.description(), template.category(),
                "You are " + template.name() + ". " + template.description() + " Return a JSON object that follows the output schema.",
                request.accountId(), request.model(), 0.2, 2000,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("type", "object"),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("type", "object"),
                tools, List.of("EXECUTE", "VIEW_LOGS", "VIEW_OUTPUT"), 90, 1, request.projectId(), true));
    }

    @GetMapping
    public AgentPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                          @RequestParam(required = false) UUID projectId, @RequestParam(required = false) String category,
                          @RequestParam(required = false) String search, @RequestParam(required = false) Boolean enabled) {
        return agents.list(page, size, projectId, category, search, enabled);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentResponse create(@Valid @RequestBody AgentRequest request) { return agents.create(request); }

    @GetMapping("/{id}")
    public AgentResponse get(@PathVariable UUID id) { return agents.get(id); }

    @PutMapping("/{id}")
    public AgentResponse update(@PathVariable UUID id, @Valid @RequestBody AgentRequest request) { return agents.update(id, request); }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentResponse duplicate(@PathVariable UUID id, @RequestBody(required = false) DuplicateRequest request) {
        return agents.duplicate(id, request == null ? null : request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { agents.delete(id); }

    @PostMapping("/{id}/enable")
    public AgentResponse enable(@PathVariable UUID id) { return agents.setEnabled(id, true); }

    @PostMapping("/{id}/disable")
    public AgentResponse disable(@PathVariable UUID id) { return agents.setEnabled(id, false); }

    @GetMapping("/{id}/versions")
    public List<AgentVersion> versions(@PathVariable UUID id) { return agents.versions(id); }

    @PostMapping("/{id}/versions/{version}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID id, @PathVariable int version) { agents.restoreVersion(id, version); }

    @PostMapping("/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExecutionResponse test(@PathVariable UUID id, @Valid @RequestBody ExecuteRequest request) {
        return executions.enqueue(id, request.input(), null);
    }

    @PostMapping("/{id}/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExecutionResponse execute(@PathVariable UUID id, @Valid @RequestBody ExecuteRequest request) {
        return executions.enqueue(id, request.input(), null);
    }
}
