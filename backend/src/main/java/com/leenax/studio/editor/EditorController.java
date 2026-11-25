package com.leenax.studio.editor;

import com.leenax.studio.common.PageResponse;
import com.leenax.studio.editor.EditorDtos.CreateTimelineRequest;
import com.leenax.studio.editor.EditorDtos.RenderJobResponse;
import com.leenax.studio.editor.EditorDtos.RenderRequest;
import com.leenax.studio.editor.EditorDtos.RenderStatus;
import com.leenax.studio.editor.EditorDtos.SaveDocumentRequest;
import com.leenax.studio.editor.EditorDtos.SaveResult;
import com.leenax.studio.editor.EditorDtos.TimelineResponse;
import com.leenax.studio.editor.EditorDtos.TimelineSummary;
import com.leenax.studio.editor.EditorDtos.UpdateTimelineRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/api/v1/editor")
public class EditorController {

    private final TimelineService timelines;
    private final RenderService renders;

    public EditorController(TimelineService timelines, RenderService renders) {
        this.timelines = timelines;
        this.renders = renders;
    }

    @GetMapping("/timelines")
    public PageResponse<TimelineSummary> list(@RequestParam(required = false) UUID projectId, @RequestParam(defaultValue = "0") int page) {
        return timelines.list(projectId, page);
    }

    @PostMapping("/timelines")
    @ResponseStatus(HttpStatus.CREATED)
    public TimelineResponse create(@Valid @RequestBody CreateTimelineRequest req) {
        return timelines.create(req);
    }

    @GetMapping("/timelines/{id}")
    public TimelineResponse get(@PathVariable UUID id) {
        return timelines.get(id);
    }

    @PatchMapping("/timelines/{id}")
    public TimelineResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTimelineRequest req) {
        return timelines.update(id, req);
    }

    /** Autosave of the editor document. 409 when another tab saved a newer revision. */
    @PutMapping("/timelines/{id}/document")
    public SaveResult save(@PathVariable UUID id, @Valid @RequestBody SaveDocumentRequest req) {
        return timelines.saveDocument(id, req);
    }

    @PostMapping("/timelines/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public TimelineResponse duplicate(@PathVariable UUID id) {
        return timelines.duplicate(id);
    }

    @DeleteMapping("/timelines/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        timelines.delete(id);
    }

    // ---- exports -------------------------------------------------------------

    @GetMapping("/renders/status")
    public RenderStatus renderStatus() {
        return new RenderStatus(renders.available());
    }

    @PostMapping("/timelines/{id}/renders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RenderJobResponse render(@PathVariable UUID id, @Valid @RequestBody RenderRequest req) {
        return renders.start(id, req);
    }

    @GetMapping("/renders")
    public List<RenderJobResponse> renders(@RequestParam(required = false) UUID timelineId) {
        return renders.list(timelineId);
    }

    @GetMapping("/renders/{id}")
    public RenderJobResponse getRender(@PathVariable UUID id) {
        return renders.get(id);
    }

    @PostMapping("/renders/{id}/cancel")
    public RenderJobResponse cancel(@PathVariable UUID id) {
        return renders.cancel(id);
    }

    @DeleteMapping("/renders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRender(@PathVariable UUID id) {
        renders.delete(id);
    }
}
