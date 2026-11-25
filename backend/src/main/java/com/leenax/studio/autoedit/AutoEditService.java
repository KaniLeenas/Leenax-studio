package com.leenax.studio.autoedit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.autoedit.AutoEditDtos.AutoEditRequest;
import com.leenax.studio.autoedit.AutoEditDtos.JobResponse;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.projects.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AutoEditService {

    static final int MAX_ACTIVE = 3;

    private final AutoEditJobRepository jobs;
    private final MediaService media;
    private final ProjectRepository projects;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public AutoEditService(AutoEditJobRepository jobs, MediaService media, ProjectRepository projects, ObjectMapper mapper, AuditService audit) {
        this.jobs = jobs;
        this.media = media;
        this.projects = projects;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional
    public JobResponse create(AutoEditRequest req) {
        validate(req);
        if (jobs.countByStatusIn(List.of(AutoEditJob.Status.QUEUED, AutoEditJob.Status.RUNNING)) >= MAX_ACTIVE) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "There are already " + MAX_ACTIVE + " automatic edits running. Wait for one to finish.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = mapper.convertValue(req, Map.class);
        AutoEditJob job = jobs.save(new AutoEditJob(req.mode(), req.name().strip(), req.projectId(), settings));
        audit.record("AUTO_EDIT_QUEUED", "auto_edit_job", job.getId(), Map.of("mode", req.mode().name(), "name", job.getName()));
        return response(job);
    }

    /** Checks the request makes sense for its mode before any credits are spent. */
    void validate(AutoEditRequest r) {
        List<UUID> footage = r.footageIds() == null ? List.of() : r.footageIds();
        if (footage.size() != footage.stream().distinct().count()) throw ApiException.badRequest("The same file is listed twice");
        List<MediaAsset> assets = media.findAssets(footage);
        if (assets.size() != footage.size()) throw ApiException.badRequest("Some of the chosen files no longer exist");
        if (r.projectId() != null && !projects.existsById(r.projectId())) throw ApiException.notFound("Project");
        if (r.musicMediaId() != null) {
            List<MediaAsset> music = media.findAssets(List.of(r.musicMediaId()));
            if (music.isEmpty() || music.get(0).getKind() != MediaKind.AUDIO) throw ApiException.badRequest("Background music must be an audio file");
        }
        boolean hasText = r.textAccountId() != null && r.textModel() != null && !r.textModel().isBlank();
        switch (r.mode()) {
            case SCRIPT_TO_VIDEO -> {
                boolean script = r.script() != null && !r.script().isBlank();
                boolean topic = r.topic() != null && !r.topic().isBlank();
                if (!script && !topic) throw ApiException.badRequest("Write a script, or describe the topic and let AI write it");
                if (!script && !hasText) throw ApiException.badRequest("Choose an AI text account and model to write the script");
                if (r.describeFootage() && !hasText) throw ApiException.badRequest("Choose an AI text account and model to look at your footage");
                requireKinds(assets, Set.of(MediaKind.VIDEO, MediaKind.IMAGE), "B-roll must be videos or images");
                if (r.imageAccountId() != null && (r.imageModel() == null || r.imageModel().isBlank())) {
                    throw ApiException.badRequest("Choose an image model");
                }
            }
            case TALKING_HEAD -> {
                if (footage.isEmpty()) throw ApiException.badRequest("Choose at least one video of you talking");
                requireKinds(assets, Set.of(MediaKind.VIDEO), "Choose videos (with sound) to clean up");
                if (r.transcriptionAccountId() == null) throw ApiException.badRequest("Choose an ElevenLabs or OpenAI account to find the speech");
            }
            case HIGHLIGHTS -> {
                if (footage.size() != 1) throw ApiException.badRequest("Choose one long video");
                requireKinds(assets, Set.of(MediaKind.VIDEO), "Highlights need a video");
                if (r.transcriptionAccountId() == null) throw ApiException.badRequest("Choose an ElevenLabs or OpenAI account to find the speech");
                if (!hasText) throw ApiException.badRequest("Choose an AI text account and model to pick the best moments");
                int min = r.minSeconds() == null ? 20 : r.minSeconds();
                int max = r.maxSeconds() == null ? 60 : r.maxSeconds();
                if (min >= max) throw ApiException.badRequest("The shortest length must be less than the longest");
            }
        }
    }

    private static void requireKinds(List<MediaAsset> assets, Set<MediaKind> kinds, String message) {
        if (assets.stream().anyMatch(a -> !kinds.contains(a.getKind()))) throw ApiException.badRequest(message);
    }

    @Transactional(readOnly = true)
    public List<JobResponse> list() {
        return jobs.findTop30ByOrderByCreatedAtDesc().stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public JobResponse get(UUID id) {
        return response(find(id));
    }

    @Transactional
    public JobResponse cancel(UUID id) {
        AutoEditJob job = find(id);
        if (!job.isActive()) throw new ApiException(ErrorCode.CONFLICT, "This automatic edit has already finished");
        job.setStatus(AutoEditJob.Status.CANCELLED);
        job.setCompletedAt(java.time.Instant.now());
        job.setStep("Cancelled");
        return response(jobs.save(job));
    }

    /** Runs the same request again (e.g. after fixing an account). Transcripts and descriptions are reused. */
    @Transactional
    public JobResponse rerun(UUID id) {
        AutoEditJob old = find(id);
        if (old.isActive()) throw new ApiException(ErrorCode.CONFLICT, "This automatic edit is still running");
        AutoEditRequest req = mapper.convertValue(old.getSettings(), AutoEditRequest.class);
        return create(req);
    }

    @Transactional
    public void delete(UUID id) {
        AutoEditJob job = find(id);
        if (job.isActive()) throw new ApiException(ErrorCode.CONFLICT, "Cancel it first");
        jobs.delete(job);
    }

    private AutoEditJob find(UUID id) {
        return jobs.findById(id).orElseThrow(() -> ApiException.notFound("Automatic edit"));
    }

    JobResponse response(AutoEditJob j) {
        return new JobResponse(j.getId(), j.getMode(), j.getName(), j.getProjectId(), j.getStatus(), j.getStep(), j.getProgress().doubleValue(),
                j.getSettings(), j.getResult(), j.getErrorMessage(), j.getCreatedAt(), j.getStartedAt(), j.getCompletedAt());
    }

    static List<String> notes(Map<String, Object> result) {
        Object n = result == null ? null : result.get("notes");
        List<String> out = new ArrayList<>();
        if (n instanceof List<?> l) l.forEach(x -> out.add(String.valueOf(x)));
        return out;
    }
}
