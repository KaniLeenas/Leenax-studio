package com.leenax.studio.scripts;

import com.leenax.studio.scripts.ScriptDtos.*;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scripts")
public class ScriptController {

    private final ScriptService service;

    public ScriptController(ScriptService service) {
        this.service = service;
    }

    @PostMapping("/ideas")
    public IdeasResponse ideas(@Valid @RequestBody IdeasRequest r) {
        return service.ideas(r);
    }

    @PostMapping("/write")
    @ResponseStatus(HttpStatus.CREATED)
    public ScriptResponse write(@Valid @RequestBody WriteRequest r) {
        return service.write(r);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScriptResponse create(@Valid @RequestBody CreateRequest r) {
        return service.create(r);
    }

    @GetMapping
    public List<ScriptResponse> list(@RequestParam(required = false) UUID projectId) {
        return service.list(projectId);
    }

    @GetMapping("/{id}")
    public ScriptResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public ScriptResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        return service.update(id, r);
    }

    @PostMapping("/{id}/rewrite")
    @ResponseStatus(HttpStatus.CREATED)
    public ScriptResponse rewrite(@PathVariable UUID id, @Valid @RequestBody RewriteRequest r) {
        return service.rewrite(id, r);
    }

    @PostMapping("/{id}/metadata")
    public ScriptResponse metadata(@PathVariable UUID id, @Valid @RequestBody MetadataRequest r) {
        return service.metadata(id, r);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
