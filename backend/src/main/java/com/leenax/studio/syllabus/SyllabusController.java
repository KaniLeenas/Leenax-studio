package com.leenax.studio.syllabus;

import com.leenax.studio.approvals.ReviewDtos.ResourceState;
import com.leenax.studio.syllabus.SyllabusService.GenerateRequest;
import com.leenax.studio.syllabus.SyllabusService.JobResponse;
import com.leenax.studio.syllabus.SyllabusService.Overview;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Syllabus analysis and topic building (E3). Versions and decisions are under /reviews. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class SyllabusController {

    private final SyllabusService service;

    public SyllabusController(SyllabusService service) {
        this.service = service;
    }

    @GetMapping("/syllabus/analysis")
    public Overview overview(@PathVariable UUID projectId) {
        return service.overview(projectId);
    }

    @PostMapping("/syllabus/analysis")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse generate(@PathVariable UUID projectId, @Valid @RequestBody GenerateRequest req) {
        return service.generate(projectId, req);
    }

    @PostMapping("/syllabus/analysis/{jobId}/cancel")
    public JobResponse cancel(@PathVariable UUID projectId, @PathVariable UUID jobId) {
        return service.cancel(projectId, jobId);
    }

    @PostMapping("/syllabus/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceState writeByHand(@PathVariable UUID projectId) {
        return service.writeByHand(projectId);
    }

    /** Builds the topic list from the approved syllabus, or updates it to a newer approved syllabus. */
    @PostMapping("/topics/from-syllabus")
    public ResourceState updateTopics(@PathVariable UUID projectId) {
        return service.updateTopics(projectId);
    }
}
