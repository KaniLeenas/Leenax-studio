package com.leenax.studio.subtitles;

import com.leenax.studio.subtitles.SubtitleDtos.CreateTrackRequest;
import com.leenax.studio.subtitles.SubtitleDtos.TrackResponse;
import com.leenax.studio.subtitles.SubtitleDtos.TrackSummary;
import com.leenax.studio.subtitles.SubtitleDtos.TranscribeRequest;
import com.leenax.studio.subtitles.SubtitleDtos.TranslateRequest;
import com.leenax.studio.subtitles.SubtitleDtos.UpdateTrackRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/subtitles")
public class SubtitleController {

    private final SubtitleService service;

    public SubtitleController(SubtitleService service) {
        this.service = service;
    }

    @GetMapping
    public List<TrackSummary> list(@RequestParam UUID mediaId) {
        return service.list(mediaId);
    }

    @PostMapping("/transcribe")
    @ResponseStatus(HttpStatus.CREATED)
    public TrackResponse transcribe(@Valid @RequestBody TranscribeRequest req) {
        return service.transcribe(req);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TrackResponse create(@Valid @RequestBody CreateTrackRequest req) {
        return service.create(req);
    }

    @GetMapping("/{id}")
    public TrackResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public TrackResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTrackRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/translate")
    @ResponseStatus(HttpStatus.CREATED)
    public TrackResponse translate(@PathVariable UUID id, @Valid @RequestBody TranslateRequest req) {
        return service.translate(id, req);
    }

    /** ?format=srt|vtt — downloads with a filename; ?inline=true is used by the video preview's caption track. */
    @GetMapping("/{id}/export")
    public ResponseEntity<String> export(@PathVariable UUID id, @RequestParam(defaultValue = "srt") String format,
                                         @RequestParam(defaultValue = "false") boolean inline) {
        var e = service.export(id, format);
        String encoded = URLEncoder.encode(e.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(e.contentType()))
                .header("Content-Disposition", (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded)
                .header("Cache-Control", "no-store")
                .body(e.body());
    }
}
