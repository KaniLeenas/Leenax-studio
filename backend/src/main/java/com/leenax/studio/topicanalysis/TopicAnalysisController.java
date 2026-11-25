package com.leenax.studio.topicanalysis;

import com.leenax.studio.topicanalysis.TopicAnalysisService.Next;
import com.leenax.studio.topicanalysis.TopicAnalysisService.Overview;
import com.leenax.studio.topicanalysis.TopicAnalysisService.RunRequest;
import com.leenax.studio.topicanalysis.TopicAnalysisService.Settings;
import com.leenax.studio.topicanalysis.TopicAnalysisService.SettingsRequest;
import com.leenax.studio.topicanalysis.TopicAnalysisService.Started;
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

import java.util.UUID;

/**
 * Topic analysis and the automatic steps after it (E11). An analysis is read, edited, reviewed
 * and approved through the approval routes (/reviews/resources/{id}); these are its own.
 */
@RestController
@RequestMapping("/api/v1")
public class TopicAnalysisController {

    private final TopicAnalysisService service;

    public TopicAnalysisController(TopicAnalysisService service) {
        this.service = service;
    }

    @GetMapping("/projects/{projectId}/topic-analysis")
    public Overview overview(@PathVariable UUID projectId) {
        return service.overview(projectId);
    }

    @PutMapping("/projects/{projectId}/topic-analysis/settings")
    public Settings saveSettings(@PathVariable UUID projectId, @Valid @RequestBody SettingsRequest req) {
        return service.saveSettings(projectId, req);
    }

    @PostMapping("/projects/{projectId}/topic-analysis/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Started run(@PathVariable UUID projectId, @Valid @RequestBody RunRequest req) {
        return service.run(projectId, req);
    }

    /** What has followed an approved analysis: notes, questions, reports, presentation, video. */
    @GetMapping("/topic-analysis/{resourceId}/next")
    public Next next(@PathVariable UUID resourceId) {
        return service.next(resourceId);
    }
}
