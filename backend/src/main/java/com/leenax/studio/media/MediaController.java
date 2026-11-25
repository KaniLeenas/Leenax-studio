package com.leenax.studio.media;

import com.leenax.studio.common.PageResponse;
import com.leenax.studio.media.MediaDtos.DownloadUrlResponse;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.media.MediaDtos.UpdateMediaRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MediaResponse upload(@RequestPart("file") MultipartFile file,
                                @RequestParam(required = false) UUID projectId) {
        return service.upload(file, projectId);
    }

    @GetMapping
    public PageResponse<MediaResponse> list(@RequestParam(required = false) MediaKind kind,
                                            @RequestParam(required = false) UUID projectId,
                                            @RequestParam(defaultValue = "false") boolean unassigned,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "30") int size) {
        return PageResponse.of(service.list(kind, projectId, unassigned, search, page, size));
    }

    @GetMapping("/{id}")
    public MediaResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/download-url")
    public DownloadUrlResponse downloadUrl(@PathVariable UUID id) {
        return service.downloadUrl(id);
    }

    /**
     * Streams the file through the app itself. Used where the browser needs the
     * bytes on our own origin, e.g. images placed on the Thumbnail Studio canvas
     * (a canvas with cross-origin images cannot be exported).
     */
    @GetMapping("/{id}/content")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> content(@PathVariable UUID id) {
        var asset = service.findAsset(id);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .contentLength(asset.getSizeBytes())
                .header("Cache-Control", "private, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .body(new org.springframework.core.io.InputStreamResource(service.openContent(asset)));
    }

    @PatchMapping("/{id}")
    public MediaResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateMediaRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
