package com.leenax.studio.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.common.TempFiles;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.media.MediaSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One step of an export: submit to the renderer, check progress, or fetch the
 * finished MP4 into the media library. Called by {@link RenderWorker}.
 */
@Component
public class RenderProcessor {

    private static final Logger log = LoggerFactory.getLogger(RenderProcessor.class);
    static final Duration POLL = Duration.ofSeconds(3);
    static final Duration MAX_RENDER = Duration.ofHours(3);
    static final Duration MEDIA_LINK = Duration.ofHours(6);
    static final int MAX_ATTEMPTS = 6;

    private final RenderJobRepository jobs;
    private final TimelineRepository timelines;
    private final RendererClient renderer;
    private final MediaService media;
    private final ObjectMapper mapper;
    private final RenderValidator validator;

    public RenderProcessor(RenderJobRepository jobs, TimelineRepository timelines, RendererClient renderer, MediaService media, ObjectMapper mapper,
                           RenderValidator validator) {
        this.validator = validator;
        this.jobs = jobs;
        this.timelines = timelines;
        this.renderer = renderer;
        this.media = media;
        this.mapper = mapper;
    }

    public void process(UUID id) {
        RenderJob job = jobs.findById(id).orElse(null);
        if (job == null || !job.isActive()) return;
        try {
            if (job.getStatus() == RenderJob.Status.QUEUED) submit(job);
            else poll(job);
        } catch (RendererClient.UnknownRenderException e) {
            // The renderer restarted and lost the render: start again (a few times).
            job.setAttempts(job.getAttempts() + 1);
            if (job.getAttempts() >= 3) fail(job, "The render service restarted during the export. Try again.");
            else {
                job.setStatus(RenderJob.Status.QUEUED);
                job.setProgress(0);
                job.setNextAttemptAt(Instant.now());
            }
        } catch (RendererClient.UnavailableException e) {
            job.setAttempts(job.getAttempts() + 1);
            if (job.getAttempts() >= MAX_ATTEMPTS) fail(job, "The render service is not reachable. Check that it is running.");
            else job.setNextAttemptAt(Instant.now().plus(backoff(job.getAttempts())));
            log.warn("Render {}: {}", id, e.getMessage());
        } catch (IllegalArgumentException e) {
            fail(job, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Render {} failed", id, e);
            fail(job, "Export failed: " + e.getMessage());
        }
        job.setLockedUntil(null);
        try {
            jobs.save(job);
        } catch (ObjectOptimisticLockingFailureException e) {
            // cancelled meanwhile: the user's change wins
            if (job.getStatus() == RenderJob.Status.RENDERING || job.getStatus() == RenderJob.Status.QUEUED) renderer.cancel(job.rendererId());
        }
    }

    private void submit(RenderJob job) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("timeline", job.getDocument());
        props.put("media", mediaRefs(job));
        RenderSettings s = RenderSettings.fromMap(job.getSettings());
        int[] size = s.outputSize(num(job.getDocument().get("width"), 1920), num(job.getDocument().get("height"), 1080));
        props.put("output", Map.of("width", size[0], "height", size[1]));
        renderer.submit(job.rendererId(), props, s.rendererParams());
        job.setStatus(RenderJob.Status.RENDERING);
        if (job.getSubmittedAt() == null) job.setSubmittedAt(Instant.now());
        job.setNextAttemptAt(Instant.now().plus(POLL));
    }

    private void poll(RenderJob job) {
        RendererClient.Status st = renderer.status(job.rendererId());
        switch (st.status()) {
            case "queued", "rendering" -> {
                job.setProgress(st.progress() * 0.97); // the last few % are saving to the library
                if (job.getSubmittedAt() != null && job.getSubmittedAt().plus(MAX_RENDER).isBefore(Instant.now())) {
                    renderer.cancel(job.rendererId());
                    fail(job, "The export took longer than 3 hours and was stopped");
                } else {
                    job.setNextAttemptAt(Instant.now().plus(POLL));
                }
            }
            case "done" -> finish(job);
            case "cancelled" -> {
                job.setStatus(RenderJob.Status.CANCELLED);
                job.setCompletedAt(Instant.now());
            }
            default -> fail(job, st.error() == null ? "The export failed" : "The export failed: " + st.error());
        }
    }

    /** Downloads the MP4, checks it with ffprobe, and saves it to the media library (and the timeline's project). */
    private void finish(RenderJob job) {
        try (TempFiles tmp = new TempFiles()) {
            var file = tmp.create(".mp4");
            renderer.download(job.rendererId(), file);
            RenderValidator.Result check = validator.validate(file,
                    RenderValidator.expected(job.getDocument(), RenderSettings.fromMap(job.getSettings()), mapper));
            job.setValidation(check.toMap());
            if (!check.ok()) {
                renderer.cancel(job.rendererId());
                fail(job, "The file failed its checks: " + String.join("; ", check.problems()));
                return;
            }
            String name = job.getTimelineName().replaceAll("[\\\\/:*?\"<>|]", "_");
            MediaAsset asset = media.ingest(file, name + ".mp4", MediaSource.RENDER, job.getProjectId());
            job.setOutputMediaId(asset.getId());
            job.setStatus(RenderJob.Status.COMPLETED);
            job.setProgress(1);
            job.setCompletedAt(Instant.now());
            renderer.cancel(job.rendererId()); // frees the renderer's copy
            timelines.findById(job.getTimelineId()).ifPresent(t -> {
                t.setPosterMediaId(asset.getId());
                timelines.save(t);
            });
        }
    }

    private Map<String, Object> mediaRefs(RenderJob job) {
        List<UUID> ids;
        try {
            ids = List.copyOf(TimelineDocument.check(mapper.valueToTree(job.getDocument())).mediaIds());
        } catch (TimelineDocument.InvalidTimelineException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        List<MediaAsset> found = media.findAssets(ids);
        if (found.size() != ids.size()) throw new IllegalArgumentException("A file used in this timeline was deleted from the media library");
        Map<String, Object> refs = new LinkedHashMap<>();
        for (MediaAsset a : found) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", a.getId().toString());
            r.put("kind", a.getKind().name());
            r.put("url", media.internalUrl(a, MEDIA_LINK));
            if (a.getDurationMs() != null) r.put("durationMs", a.getDurationMs());
            if (a.getWidth() != null) r.put("width", a.getWidth());
            if (a.getHeight() != null) r.put("height", a.getHeight());
            r.put("filename", a.getOriginalFilename());
            refs.put(a.getId().toString(), r);
        }
        return refs;
    }

    private static void fail(RenderJob job, String message) {
        job.setStatus(RenderJob.Status.FAILED);
        job.setErrorMessage(message);
        job.setCompletedAt(Instant.now());
    }

    static Duration backoff(int attempt) {
        long s = Math.min(300, 15L << Math.max(0, attempt - 1));
        return Duration.ofSeconds(s);
    }

    private static int num(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }
}
