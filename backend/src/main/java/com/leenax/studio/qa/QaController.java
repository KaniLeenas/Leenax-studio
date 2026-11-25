package com.leenax.studio.qa;

import com.leenax.studio.approvals.ReviewDtos.ResourceState;
import com.leenax.studio.qa.QaService.Decision;
import com.leenax.studio.qa.QaService.JobInfo;
import com.leenax.studio.qa.QaService.Overview;
import com.leenax.studio.qa.QaService.Reason;
import com.leenax.studio.qa.QaService.ResourceQa;
import com.leenax.studio.qa.QaService.RunRequest;
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

/** Academic QA (E5). */
@RestController
@RequestMapping("/api/v1")
public class QaController {

    private final QaService qa;

    public QaController(QaService qa) {
        this.qa = qa;
    }

    @GetMapping("/projects/{projectId}/qa")
    public Overview overview(@PathVariable UUID projectId) {
        return qa.overview(projectId);
    }

    @GetMapping("/qa/resources/{resourceId}")
    public ResourceQa forResource(@PathVariable UUID resourceId) {
        return qa.forResource(resourceId);
    }

    @PostMapping("/qa/resources/{resourceId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobInfo run(@PathVariable UUID resourceId, @Valid @RequestBody RunRequest req) {
        return qa.start(resourceId, req);
    }

    @PostMapping("/qa/corrections/{correctionId}/accept")
    public ResourceQa accept(@PathVariable UUID correctionId, @Valid @RequestBody(required = false) Decision d) {
        return qa.accept(correctionId, d);
    }

    @PostMapping("/qa/corrections/{correctionId}/reject")
    public ResourceQa reject(@PathVariable UUID correctionId, @Valid @RequestBody(required = false) Decision d) {
        return qa.reject(correctionId, d);
    }

    @PostMapping("/qa/issues/{issueId}/dismiss")
    public ResourceQa dismiss(@PathVariable UUID issueId, @Valid @RequestBody Reason reason) {
        return qa.dismiss(issueId, reason);
    }

    @PostMapping("/qa/issues/{issueId}/reopen")
    public ResourceQa reopen(@PathVariable UUID issueId, @Valid @RequestBody Reason reason) {
        return qa.reopen(issueId, reason);
    }

    @PostMapping("/projects/{projectId}/qa/reports/{topicId}")
    public ResourceState makeReport(@PathVariable UUID projectId, @PathVariable String topicId) {
        return qa.makeReport(projectId, topicId);
    }
}
