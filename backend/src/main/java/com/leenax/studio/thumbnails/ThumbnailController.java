package com.leenax.studio.thumbnails;

import com.leenax.studio.thumbnails.ThumbnailDtos.DesignRequest;
import com.leenax.studio.thumbnails.ThumbnailDtos.DesignResponse;
import com.leenax.studio.thumbnails.ThumbnailDtos.DesignSummary;
import com.leenax.studio.thumbnails.ThumbnailDtos.DesignUpdate;
import com.leenax.studio.thumbnails.ThumbnailDtos.DuplicateRequest;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/thumbnails")
public class ThumbnailController {

    private final ThumbnailService service;

    public ThumbnailController(ThumbnailService service) {
        this.service = service;
    }

    /** ?templates=true lists saved templates instead of designs. */
    @GetMapping
    public List<DesignSummary> list(@RequestParam(defaultValue = "false") boolean templates) {
        return service.list(templates);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DesignResponse create(@Valid @RequestBody DesignRequest req) {
        return service.create(req);
    }

    @GetMapping("/{id}")
    public DesignResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public DesignResponse update(@PathVariable UUID id, @Valid @RequestBody DesignUpdate req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public DesignResponse duplicate(@PathVariable UUID id, @Valid @RequestBody(required = false) DuplicateRequest req) {
        return service.duplicate(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /** Multipart "file": the PNG/JPG rendered by the editor. Saved to the media library. */
    @PostMapping(path = "/{id}/export", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DesignResponse export(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return service.export(id, file);
    }
}
