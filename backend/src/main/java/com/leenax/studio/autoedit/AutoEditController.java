package com.leenax.studio.autoedit;

import com.leenax.studio.autoedit.AutoEditDtos.AutoEditRequest;
import com.leenax.studio.autoedit.AutoEditDtos.JobResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auto-edit/jobs")
public class AutoEditController {

    private final AutoEditService service;

    public AutoEditController(AutoEditService service) {
        this.service = service;
    }

    @GetMapping
    public List<JobResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse create(@Valid @RequestBody AutoEditRequest req) {
        return service.create(req);
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/cancel")
    public JobResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @PostMapping("/{id}/rerun")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse rerun(@PathVariable UUID id) {
        return service.rerun(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
