package com.leenax.studio.storyboard;

import com.leenax.studio.storyboard.StoryboardDtos.*;
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
@RequestMapping("/api/v1/storyboards")
public class StoryboardController {

    private final StoryboardService service;

    public StoryboardController(StoryboardService service) {
        this.service = service;
    }

    @GetMapping
    public List<StoryboardSummary> list(@RequestParam(required = false) UUID projectId) {
        return service.list(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoryboardResponse create(@Valid @RequestBody CreateRequest r) {
        return service.create(r);
    }

    @PostMapping("/from-script")
    @ResponseStatus(HttpStatus.CREATED)
    public StoryboardResponse fromScript(@Valid @RequestBody FromScriptRequest r) {
        return service.fromScript(r);
    }

    @GetMapping("/{id}")
    public StoryboardResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public StoryboardResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        return service.update(id, r);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/scenes")
    @ResponseStatus(HttpStatus.CREATED)
    public StoryboardResponse addScene(@PathVariable UUID id, @RequestParam(required = false) Integer at,
                                       @Valid @RequestBody(required = false) SceneRequest r) {
        return service.addScene(id, r, at);
    }

    @PutMapping("/{id}/scenes/order")
    public StoryboardResponse reorder(@PathVariable UUID id, @Valid @RequestBody ReorderRequest r) {
        return service.reorder(id, r);
    }

    @PutMapping("/scenes/{sceneId}")
    public StoryboardResponse updateScene(@PathVariable UUID sceneId, @Valid @RequestBody SceneRequest r) {
        return service.updateScene(sceneId, r);
    }

    @PostMapping("/scenes/{sceneId}/duplicate")
    public StoryboardResponse duplicateScene(@PathVariable UUID sceneId) {
        return service.duplicateScene(sceneId);
    }

    @DeleteMapping("/scenes/{sceneId}")
    public StoryboardResponse deleteScene(@PathVariable UUID sceneId) {
        return service.deleteScene(sceneId);
    }

    @PostMapping("/scenes/{sceneId}/image")
    public StoryboardResponse generateImage(@PathVariable UUID sceneId, @Valid @RequestBody SceneImageRequest r) {
        return service.generateImage(sceneId, r);
    }

    @PostMapping("/scenes/{sceneId}/video")
    public StoryboardResponse generateVideo(@PathVariable UUID sceneId, @Valid @RequestBody SceneVideoRequest r) {
        return service.generateVideo(sceneId, r);
    }

    @PostMapping("/scenes/{sceneId}/dialogue-voice")
    public StoryboardResponse voiceDialogue(@PathVariable UUID sceneId) {
        return service.voiceDialogue(sceneId);
    }

    @PostMapping("/{id}/narration")
    public StoryboardResponse narrate(@PathVariable UUID id, @Valid @RequestBody NarrateRequest r) {
        return service.narrate(id, r);
    }

    @PostMapping("/{id}/export")
    public ExportResponse export(@PathVariable UUID id, @Valid @RequestBody ExportRequest r) {
        return service.export(id, r);
    }
}
