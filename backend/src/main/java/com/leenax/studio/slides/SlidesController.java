package com.leenax.studio.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.slides.SlidesService.GenerateRequest;
import com.leenax.studio.slides.SlidesService.Overview;
import com.leenax.studio.slides.SlidesService.Proposal;
import com.leenax.studio.slides.SlidesService.RegenerateRequest;
import com.leenax.studio.syllabus.AnalysisStore.JobRow;
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

/**
 * The presentation studio (E7). Decks are edited, reviewed and exported through the approval and
 * export routes (/versions/{id}/export?format=pptx or pdf); these are the studio's own.
 */
@RestController
@RequestMapping("/api/v1")
public class SlidesController {

    private final SlidesService slides;

    public SlidesController(SlidesService slides) {
        this.slides = slides;
    }

    @GetMapping("/projects/{projectId}/slides")
    public Overview overview(@PathVariable UUID projectId) {
        return slides.overview(projectId);
    }

    @PostMapping("/projects/{projectId}/slides")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRow generate(@PathVariable UUID projectId, @Valid @RequestBody GenerateRequest req) {
        return slides.start(projectId, req);
    }

    /** How a deck being edited fits (nothing is saved). */
    @PostMapping("/projects/{projectId}/slides/fit")
    public JsonNode fit(@PathVariable UUID projectId, @RequestBody JsonNode deck) {
        return slides.fit(projectId, deck);
    }

    /** A new version of one slide, proposed; the person keeps it by saving the draft. */
    @PostMapping("/slides/{resourceId}/slides/{slideId}/regenerate")
    public Proposal regenerate(@PathVariable UUID resourceId, @PathVariable String slideId, @Valid @RequestBody RegenerateRequest req) {
        return slides.regenerate(resourceId, slideId, req);
    }
}
