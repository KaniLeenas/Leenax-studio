package com.leenax.studio.approvals;

import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.ReviewDtos.CommentRequest;
import com.leenax.studio.approvals.ReviewDtos.ContentRequest;
import com.leenax.studio.approvals.ReviewDtos.DecisionRequest;
import com.leenax.studio.approvals.ReviewDtos.EditRequest;
import com.leenax.studio.approvals.ReviewDtos.EventResponse;
import com.leenax.studio.approvals.ReviewDtos.ResourceState;
import com.leenax.studio.approvals.ReviewDtos.VersionDetail;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Versions and decisions for approvable resources (E3). */
@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    /** kind: syllabus or topics. */
    @GetMapping("/projects/{projectId}/reviews/{kind}")
    public ResourceState state(@PathVariable UUID projectId, @PathVariable String kind) {
        return service.state(projectId, kind(kind));
    }

    @GetMapping("/reviews/resources/{resourceId}")
    public ResourceState stateOf(@PathVariable UUID resourceId) {
        return service.stateOf(resourceId);
    }

    @GetMapping("/reviews/versions/{versionId}")
    public VersionDetail version(@PathVariable UUID versionId) {
        return service.version(versionId);
    }

    @PutMapping("/reviews/versions/{versionId}")
    public VersionDetail saveDraft(@PathVariable UUID versionId, @Valid @RequestBody ContentRequest req) {
        return service.saveDraft(versionId, req.content());
    }

    @PostMapping("/reviews/resources/{resourceId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionDetail edit(@PathVariable UUID resourceId, @Valid @RequestBody EditRequest req) {
        return service.edit(resourceId, req.fromVersionId(), req.content());
    }

    @GetMapping("/reviews/resources/{resourceId}/events")
    public List<EventResponse> events(@PathVariable UUID resourceId) {
        return service.events(resourceId);
    }

    @PostMapping("/reviews/versions/{versionId}/submit")
    public VersionDetail submit(@PathVariable UUID versionId, @Valid @RequestBody(required = false) CommentRequest req) {
        return service.submit(versionId, req == null ? null : req.comment());
    }

    @PostMapping("/reviews/versions/{versionId}/withdraw")
    public VersionDetail withdraw(@PathVariable UUID versionId) {
        return service.withdraw(versionId);
    }

    @PostMapping("/reviews/versions/{versionId}/decision")
    public VersionDetail decide(@PathVariable UUID versionId, @Valid @RequestBody DecisionRequest req) {
        return service.decide(versionId, req.decision(), req.comment(), req.content());
    }

    static Kind kind(String s) {
        try {
            return Kind.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw com.leenax.studio.common.ApiException.notFound("Review type");
        }
    }
}
