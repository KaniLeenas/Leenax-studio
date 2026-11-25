package com.leenax.studio.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.common.PageResponse;
import com.leenax.studio.editor.EditorDtos.CreateTimelineRequest;
import com.leenax.studio.editor.EditorDtos.SaveDocumentRequest;
import com.leenax.studio.editor.EditorDtos.SaveResult;
import com.leenax.studio.editor.EditorDtos.TimelineResponse;
import com.leenax.studio.editor.EditorDtos.TimelineSummary;
import com.leenax.studio.editor.EditorDtos.UpdateTimelineRequest;
import com.leenax.studio.editor.TimelineDocument.InvalidTimelineException;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.projects.ProjectRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TimelineService {

    private final TimelineRepository timelines;
    private final ProjectRepository projects;
    private final MediaService media;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public TimelineService(TimelineRepository timelines, ProjectRepository projects, MediaService media, ObjectMapper mapper, AuditService audit) {
        this.timelines = timelines;
        this.projects = projects;
        this.media = media;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<TimelineSummary> list(UUID projectId, int page) {
        var pageable = PageRequest.of(Math.max(0, page), 24);
        var result = projectId == null ? timelines.findAllByOrderByUpdatedAtDesc(pageable) : timelines.findByProjectIdOrderByUpdatedAtDesc(projectId, pageable);
        return PageResponse.of(result.map(this::summary));
    }

    @Transactional(readOnly = true)
    public TimelineResponse get(UUID id) {
        return response(find(id));
    }

    @Transactional
    public TimelineResponse create(CreateTimelineRequest req) {
        TimelineDocument.Summary s = check(req.document());
        requireMedia(s.mediaIds());
        Timeline t = new Timeline(req.name().strip(), s.width(), s.height(), s.fps(), req.document());
        t.setDurationSeconds(BigDecimal.valueOf(s.durationSeconds()));
        if (req.projectId() != null) {
            if (!projects.existsById(req.projectId())) throw ApiException.notFound("Project");
            t.setProjectId(req.projectId());
        }
        t = timelines.save(t);
        audit.record("TIMELINE_CREATED", "timeline", t.getId(), Map.of("name", t.getName()));
        return response(t);
    }

    @Transactional
    public SaveResult saveDocument(UUID id, SaveDocumentRequest req) {
        Timeline t = find(id);
        if (req.revision() != t.getRevision()) {
            throw new ApiException(ErrorCode.CONFLICT, "This timeline was changed in another tab or window. Reload to get the latest version.");
        }
        TimelineDocument.Summary s = check(req.document());
        requireMedia(s.mediaIds());
        t.setDocument(req.document());
        t.setFormat(s.width(), s.height(), s.fps());
        t.setDurationSeconds(BigDecimal.valueOf(s.durationSeconds()));
        t.bumpRevision();
        t = timelines.saveAndFlush(t);
        return new SaveResult(t.getRevision(), t.getDurationSeconds(), t.getUpdatedAt());
    }

    @Transactional
    public TimelineResponse update(UUID id, UpdateTimelineRequest req) {
        Timeline t = find(id);
        if (req.name() != null) {
            if (req.name().isBlank()) throw ApiException.badRequest("Name cannot be blank");
            t.setName(req.name().strip());
        }
        if (Boolean.TRUE.equals(req.unassign())) {
            t.setProjectId(null);
        } else if (req.projectId() != null) {
            if (!projects.existsById(req.projectId())) throw ApiException.notFound("Project");
            t.setProjectId(req.projectId());
        }
        return response(t);
    }

    @Transactional
    public TimelineResponse duplicate(UUID id) {
        Timeline src = find(id);
        Timeline copy = new Timeline(src.getName() + " copy", src.getWidth(), src.getHeight(), src.getFps(), src.getDocument());
        copy.setProjectId(src.getProjectId());
        copy.setDurationSeconds(src.getDurationSeconds());
        return response(timelines.save(copy));
    }

    @Transactional
    public void delete(UUID id) {
        Timeline t = find(id);
        timelines.delete(t);
        audit.record("TIMELINE_DELETED", "timeline", id, Map.of("name", t.getName()));
    }

    // ---- helpers -------------------------------------------------------------

    TimelineDocument.Summary check(Map<String, Object> document) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(document);
            if (bytes.length > TimelineDocument.MAX_BYTES) throw ApiException.badRequest("This timeline is too large to save (over 2 MB)");
            return TimelineDocument.check(mapper.readTree(bytes));
        } catch (InvalidTimelineException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("The timeline could not be read");
        }
    }

    /** Every file a timeline uses must exist (it may have been deleted from the library). */
    List<MediaAsset> requireMedia(Set<UUID> ids) {
        List<MediaAsset> found = media.findAssets(ids);
        if (found.size() != ids.size()) {
            Set<UUID> have = found.stream().map(MediaAsset::getId).collect(Collectors.toSet());
            long missing = ids.stream().filter(i -> !have.contains(i)).count();
            throw ApiException.badRequest(missing == 1
                    ? "A file used in this timeline was deleted from the media library. Remove its clip and try again."
                    : missing + " files used in this timeline were deleted from the media library. Remove their clips and try again.");
        }
        return found;
    }

    Timeline find(UUID id) {
        return timelines.findById(id).orElseThrow(() -> ApiException.notFound("Timeline"));
    }

    private TimelineSummary summary(Timeline t) {
        return new TimelineSummary(t.getId(), t.getName(), t.getWidth(), t.getHeight(), t.getFps(), t.getDurationSeconds(), t.getProjectId(),
                media.findResponse(t.getPosterMediaId()).orElse(null), t.getUpdatedAt(), t.getCreatedAt());
    }

    private TimelineResponse response(Timeline t) {
        Set<UUID> ids;
        try {
            JsonNode doc = mapper.valueToTree(t.getDocument());
            ids = TimelineDocument.check(doc).mediaIds();
        } catch (RuntimeException e) {
            ids = Set.of();
        }
        Map<UUID, MediaResponse> refs = new LinkedHashMap<>();
        for (MediaAsset a : media.findAssets(ids)) refs.put(a.getId(), media.toResponse(a));
        return new TimelineResponse(t.getId(), t.getName(), t.getWidth(), t.getHeight(), t.getFps(), t.getDurationSeconds(), t.getProjectId(),
                t.getDocument(), t.getRevision(), refs, t.getUpdatedAt(), t.getCreatedAt());
    }
}
