package com.leenax.studio.thumbnails;

import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.common.TempFiles;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.media.MediaSource;
import com.leenax.studio.projects.ProjectRepository;
import com.leenax.studio.thumbnails.ThumbnailDtos.DesignRequest;
import com.leenax.studio.thumbnails.ThumbnailDtos.DesignResponse;
import com.leenax.studio.thumbnails.ThumbnailDtos.DesignSummary;
import com.leenax.studio.thumbnails.ThumbnailDtos.DesignUpdate;
import com.leenax.studio.thumbnails.ThumbnailDtos.DuplicateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Saves Thumbnail Studio designs. The browser renders the final PNG/JPG
 * (exactly what you see in the editor) and uploads it; it becomes ordinary image media.
 */
@Service
public class ThumbnailService {

    static final int MAX_DOCUMENT_BYTES = 512 * 1024;
    static final long MAX_EXPORT_BYTES = 25L * 1024 * 1024;

    private final ThumbnailDesignRepository designs;
    private final MediaService media;
    private final ProjectRepository projects;
    private final ObjectMapper mapper;

    public ThumbnailService(ThumbnailDesignRepository designs, MediaService media, ProjectRepository projects, ObjectMapper mapper) {
        this.designs = designs;
        this.media = media;
        this.projects = projects;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<DesignSummary> list(boolean templates) {
        return designs.findByTemplateOrderByUpdatedAtDesc(templates).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public DesignResponse get(UUID id) {
        return response(find(id));
    }

    @Transactional
    public DesignResponse create(DesignRequest req) {
        checkDocument(req.document());
        ThumbnailDesign d = new ThumbnailDesign(req.name().strip(), req.width(), req.height(), req.document());
        d.setTemplate(req.template());
        d.setProjectId(checkProject(req.projectId()));
        return response(designs.save(d));
    }

    @Transactional
    public DesignResponse update(UUID id, DesignUpdate req) {
        ThumbnailDesign d = find(id);
        if (req.name() != null) {
            if (req.name().isBlank()) throw ApiException.badRequest("Name cannot be blank");
            d.setName(req.name().strip());
        }
        if (req.width() != null || req.height() != null) {
            d.setSize(req.width() != null ? req.width() : d.getWidth(), req.height() != null ? req.height() : d.getHeight());
        }
        if (req.document() != null) {
            checkDocument(req.document());
            d.setDocument(req.document());
        }
        if (req.template() != null) d.setTemplate(req.template());
        if (req.projectId() != null) d.setProjectId(checkProject(req.projectId()));
        return response(d);
    }

    /** Copies a design or template ("use this template", "make a variation"). */
    @Transactional
    public DesignResponse duplicate(UUID id, DuplicateRequest req) {
        ThumbnailDesign src = find(id);
        String name = req != null && req.name() != null && !req.name().isBlank() ? req.name().strip() : src.getName() + " copy";
        ThumbnailDesign d = new ThumbnailDesign(name, src.getWidth(), src.getHeight(), src.getDocument());
        d.setTemplate(req != null && Boolean.TRUE.equals(req.template()));
        d.setProjectId(src.getProjectId());
        return response(designs.save(d));
    }

    @Transactional
    public void delete(UUID id) {
        designs.delete(find(id));
    }

    /** Stores the rendered image (PNG or JPG). Not transactional: the upload can be slow. */
    public DesignResponse export(UUID id, MultipartFile file) {
        ThumbnailDesign d = find(id);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("The exported image is empty");
        if (file.getSize() > MAX_EXPORT_BYTES) throw ApiException.badRequest("The exported image is larger than 25 MB");
        String ext = "image/jpeg".equals(file.getContentType()) ? ".jpg" : ".png";
        MediaAsset asset;
        try (TempFiles tmp = new TempFiles()) {
            var path = tmp.create(ext);
            file.transferTo(path);
            asset = media.ingest(path, d.getName() + ext, MediaSource.RENDER, d.getProjectId());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not save the exported image");
        }
        if (asset.getKind() != MediaKind.IMAGE) {
            media.delete(asset.getId());
            throw ApiException.badRequest("The export must be a PNG or JPG image");
        }
        return markExported(id, asset.getId());
    }

    @Transactional
    DesignResponse markExported(UUID id, UUID mediaId) {
        ThumbnailDesign d = find(id);
        d.setExportMediaId(mediaId);
        return response(designs.save(d));
    }

    private void checkDocument(java.util.Map<String, Object> doc) {
        try {
            if (mapper.writeValueAsBytes(doc).length > MAX_DOCUMENT_BYTES) {
                throw ApiException.badRequest("This design is too large to save (over 512 KB). Use media library images instead of pasted ones.");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.badRequest("The design could not be read");
        }
    }

    private UUID checkProject(UUID projectId) {
        if (projectId != null && !projects.existsById(projectId)) throw ApiException.notFound("Project");
        return projectId;
    }

    private ThumbnailDesign find(UUID id) {
        return designs.findById(id).orElseThrow(() -> ApiException.notFound("Design"));
    }

    private DesignSummary summary(ThumbnailDesign d) {
        return new DesignSummary(d.getId(), d.getName(), d.getWidth(), d.getHeight(), d.isTemplate(), d.getProjectId(),
                media.findResponse(d.getExportMediaId()).orElse(null), d.getUpdatedAt());
    }

    private DesignResponse response(ThumbnailDesign d) {
        return new DesignResponse(d.getId(), d.getName(), d.getWidth(), d.getHeight(), d.getDocument(), d.isTemplate(),
                d.getProjectId(), media.findResponse(d.getExportMediaId()).orElse(null), d.getCreatedAt(), d.getUpdatedAt());
    }
}
