package com.leenax.studio.ai.generation;

import com.leenax.studio.ai.generation.GenerationDtos.CreateResponse;
import com.leenax.studio.ai.generation.GenerationDtos.EnhanceRequest;
import com.leenax.studio.ai.generation.GenerationDtos.EnhanceResponse;
import com.leenax.studio.ai.generation.GenerationDtos.EstimateResponse;
import com.leenax.studio.ai.generation.GenerationDtos.JobResponse;
import com.leenax.studio.ai.generation.GenerationDtos.VideoGenerationRequest;
import com.leenax.studio.common.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/generation")
public class GenerationController {

    private final GenerationService service;
    private final GenerationEvents events;
    private final PromptEnhancer enhancer;

    private final com.leenax.studio.auth.CurrentUser current;

    public GenerationController(GenerationService service, GenerationEvents events, PromptEnhancer enhancer,
                                com.leenax.studio.auth.CurrentUser current) {
        this.current = current;
        this.service = service;
        this.events = events;
        this.enhancer = enhancer;
    }

    @PostMapping("/video/estimate")
    public EstimateResponse estimate(@Valid @RequestBody VideoGenerationRequest req) {
        return service.estimate(req);
    }

    /** Queues 1–4 variations. Send an Idempotency-Key header so a double click can't start (and bill) twice. */
    @PostMapping("/video")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CreateResponse create(@Valid @RequestBody VideoGenerationRequest req,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.create(req, idempotencyKey);
    }

    @GetMapping("/jobs")
    public PageResponse<JobResponse> list(@RequestParam(defaultValue = "false") boolean active,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "24") int size) {
        return PageResponse.of(service.list(active, page, size));
    }

    @GetMapping("/jobs/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/jobs/{id}/cancel")
    public JobResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @PostMapping("/jobs/{id}/regenerate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CreateResponse regenerate(@PathVariable UUID id) {
        return service.regenerate(id);
    }

    @DeleteMapping("/jobs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/enhance-prompt")
    public EnhanceResponse enhance(@Valid @RequestBody EnhanceRequest req) {
        return new EnhanceResponse(enhancer.enhance(req.accountId(), req.model(), req.prompt(), req.language(), req.targetModel()));
    }

    /** Live job updates (Server-Sent Events). */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no"); // tell Nginx not to buffer the stream
        response.setHeader("Cache-Control", "no-cache");
        var me = current.require();
        return events.subscribe(me.getId(), me.isAdmin());
    }
}
