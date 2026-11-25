package com.leenax.studio.publish;

import com.leenax.studio.publish.PublishDtos.PostRequest;
import com.leenax.studio.publish.PublishDtos.PostResponse;
import com.leenax.studio.publish.PublishDtos.RescheduleRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/publish")
public class PublishController {

    private final PublishService service;

    public PublishController(PublishService service) {
        this.service = service;
    }

    @GetMapping("/posts")
    public List<PostResponse> list(@RequestParam(required = false) Post.Status status) {
        return service.list(status);
    }

    @GetMapping("/calendar")
    public List<PostResponse> calendar(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.calendar(from, to);
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public PostResponse create(@Valid @RequestBody PostRequest req) {
        return service.create(req);
    }

    @GetMapping("/posts/{id}")
    public PostResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/posts/{id}")
    public PostResponse update(@PathVariable UUID id, @Valid @RequestBody PostRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/posts/{id}/reschedule")
    public PostResponse reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest req) {
        return service.reschedule(id, req.scheduledAt());
    }

    @PostMapping("/posts/{id}/cancel")
    public PostResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @PostMapping("/posts/{id}/targets/{targetId}/retry")
    public PostResponse retry(@PathVariable UUID id, @PathVariable UUID targetId,
                              @RequestParam(name = "confirmPublic", defaultValue = "false") boolean confirmPublic) {
        return service.retry(id, targetId, confirmPublic);
    }

    @DeleteMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
