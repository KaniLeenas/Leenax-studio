package com.leenax.studio.editor;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.editor.EditorDtos.RenderJobResponse;
import com.leenax.studio.editor.EditorDtos.RenderRequest;
import com.leenax.studio.media.MediaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RenderService {

    static final int MAX_ACTIVE = 5;

    private final RenderJobRepository jobs;
    private final TimelineService timelines;
    private final RendererClient renderer;
    private final MediaService media;
    private final AuditService audit;

    public RenderService(RenderJobRepository jobs, TimelineService timelines, RendererClient renderer, MediaService media, AuditService audit) {
        this.jobs = jobs;
        this.timelines = timelines;
        this.renderer = renderer;
        this.media = media;
        this.audit = audit;
    }

    public boolean available() {
        return renderer.configured();
    }

    /** Queues an export of the timeline as it is saved now. */
    @Transactional
    public RenderJobResponse start(UUID timelineId, RenderRequest req) {
        if (!renderer.configured()) {
            throw new ApiException(ErrorCode.CONFLICT, "The render service isn't set up. Start the renderer service (see README).");
        }
        if (!RenderSettings.RESOLUTIONS.contains(req.resolution())) throw ApiException.badRequest("Choose 720p, 1080p, 1440p or 4K");
        Timeline t = timelines.find(timelineId);
        TimelineDocument.Summary s = timelines.check(t.getDocument());
        if (s.clips() == 0) throw ApiException.badRequest("Add something to the timeline first");
        timelines.requireMedia(s.mediaIds());
        if (jobs.countByStatusIn(List.of(RenderJob.Status.QUEUED, RenderJob.Status.RENDERING)) >= MAX_ACTIVE) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "There are already " + MAX_ACTIVE + " exports waiting. Wait for one to finish.");
        }
        RenderJob job = jobs.save(new RenderJob(t, new RenderSettings(req.resolution(), req.quality())));
        audit.record("RENDER_QUEUED", "render_job", job.getId(), Map.of("timeline", t.getName(), "resolution", req.resolution()));
        return response(job);
    }

    @Transactional(readOnly = true)
    public List<RenderJobResponse> list(UUID timelineId) {
        var list = timelineId == null ? jobs.findTop30ByOrderByCreatedAtDesc() : jobs.findTop20ByTimelineIdOrderByCreatedAtDesc(timelineId);
        return list.stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public RenderJobResponse get(UUID id) {
        return response(find(id));
    }

    @Transactional
    public RenderJobResponse cancel(UUID id) {
        RenderJob job = find(id);
        if (!job.isActive()) throw new ApiException(ErrorCode.CONFLICT, "This export has already finished");
        job.setStatus(RenderJob.Status.CANCELLED);
        job.setCompletedAt(java.time.Instant.now());
        renderer.cancel(job.rendererId());
        return response(jobs.save(job));
    }

    /** Removes a finished export from the list. The video stays in the media library. */
    @Transactional
    public void delete(UUID id) {
        RenderJob job = find(id);
        if (job.isActive()) throw new ApiException(ErrorCode.CONFLICT, "Cancel the export first");
        jobs.delete(job);
    }

    private RenderJob find(UUID id) {
        return jobs.findById(id).orElseThrow(() -> ApiException.notFound("Export"));
    }

    RenderJobResponse response(RenderJob j) {
        RenderSettings s = RenderSettings.fromMap(j.getSettings());
        Object w = j.getDocument().get("width");
        Object h = j.getDocument().get("height");
        int[] size = s.outputSize(w instanceof Number n ? n.intValue() : 1920, h instanceof Number n2 ? n2.intValue() : 1080);
        return new RenderJobResponse(j.getId(), j.getTimelineId(), j.getTimelineName(), j.getProjectId(), j.getStatus(),
                j.getProgress().doubleValue(), s.resolution(), s.quality(), size[0], size[1], j.getErrorMessage(),
                media.findResponse(j.getOutputMediaId()).orElse(null), j.getCreatedAt(), j.getSubmittedAt(), j.getCompletedAt(),
                j.getValidation());
    }
}
