package com.leenax.studio.videos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.Review.Origin;
import com.leenax.studio.approvals.Review.Status;
import com.leenax.studio.approvals.ReviewService;
import com.leenax.studio.approvals.ReviewService.Target;
import com.leenax.studio.approvals.ReviewStore;
import com.leenax.studio.approvals.ReviewStore.ResourceRow;
import com.leenax.studio.approvals.ReviewStore.VersionRow;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.common.SqlStates;
import com.leenax.studio.editor.EditorDtos.CreateTimelineRequest;
import com.leenax.studio.editor.EditorDtos.RenderJobResponse;
import com.leenax.studio.editor.EditorDtos.RenderRequest;
import com.leenax.studio.editor.EditorDtos.SaveDocumentRequest;
import com.leenax.studio.editor.RenderService;
import com.leenax.studio.editor.RenderSettings;
import com.leenax.studio.editor.TimelineService;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.notes.MaterialsService;
import com.leenax.studio.notes.McqRules;
import com.leenax.studio.projects.ProjectService;
import com.leenax.studio.seo.SeoReportRules;
import com.leenax.studio.slides.SlidesRules;
import com.leenax.studio.syllabus.AnalysisStore;
import com.leenax.studio.syllabus.AnalysisStore.JobRow;
import com.leenax.studio.syllabus.AnalysisWorker;
import com.leenax.studio.videos.Videos.Narration;
import com.leenax.studio.videos.Videos.Plan;
import com.leenax.studio.videos.Videos.Segment;
import com.leenax.studio.videos.Videos.Voice;
import com.leenax.studio.voice.VoiceDtos.VoiceChoice;
import com.leenax.studio.voice.VoiceDtos.VoiceStyle;
import com.leenax.studio.voice.VoiceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lesson and quiz videos (E8). "No lesson video before its presentation is approved; no quiz
 * video before its MCQs and SEO report are approved." A video is planned from that approved
 * content (no AI writing), narrated with a chosen voice (a background job), and rendered by the
 * render service as an editor timeline. Each render is checked with ffprobe; only a version
 * with a passing render of exactly its content can be approved.
 */
@Service
public class VideoService implements AnalysisWorker.Runner {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    static final ObjectMapper JSON = new ObjectMapper();
    static final double NARRATION_GAP = 0.4;

    public record VoiceInput(UUID profileId, UUID accountId, @Size(max = 120) String voiceId, @Size(max = 120) String model,
                             Double speed, @Size(max = 200) String label) {
    }

    public record CreateRequest(@NotNull @Pattern(regexp = "LESSON|QUIZ") String kind, @NotBlank @Size(max = 40) String topicId,
                                @Size(max = 12) String level, @Pattern(regexp = "EN|TA") String language, @Pattern(regexp = "16:9|9:16") String aspect,
                                @Valid VoiceInput voice, @Min(3) @Max(30) Integer countdown, @Min(1) @Max(30) Integer questions) {
    }

    public record NarrateRequest(@NotNull UUID versionId) {
    }

    public record StartRender(@NotNull UUID versionId, @NotNull @Min(720) @Max(2160) Integer resolution, RenderSettings.Quality quality) {
    }

    public record RenderInfo(UUID renderJobId, UUID versionId, Integer versionNo, boolean forThisContent, String status, double progress, String error,
                             MediaResponse output, JsonNode validation, UUID timelineId, Instant createdAt, Instant completedAt) {
    }

    public record VideoInfo(String kind, String level, UUID resourceId, Integer versionNo, String status, String renderStatus, boolean running,
                            String error) {
    }

    public record TopicVideos(String topicId, String title, int depth, List<String> approvedDecks, boolean mcqApproved, boolean seoApproved,
                              List<VideoInfo> videos) {
    }

    public record Overview(boolean topicsApproved, List<TopicVideos> topics, boolean canEdit, boolean rendererAvailable) {
    }

    private final ReviewService reviews;
    private final ReviewStore reviewStore;
    private final VideoStore store;
    private final AnalysisStore jobs;
    private final MaterialsService materials;
    private final ProjectService projects;
    private final VoiceService voices;
    private final TimelineService timelines;
    private final RenderService renders;
    private final MediaService media;
    private final AuditService audit;

    public VideoService(ReviewService reviews, ReviewStore reviewStore, VideoStore store, AnalysisStore jobs, MaterialsService materials,
                        ProjectService projects, VoiceService voices, TimelineService timelines, RenderService renders, MediaService media,
                        AuditService audit) {
        this.reviews = reviews;
        this.reviewStore = reviewStore;
        this.store = store;
        this.jobs = jobs;
        this.materials = materials;
        this.projects = projects;
        this.voices = voices;
        this.timelines = timelines;
        this.renders = renders;
        this.media = media;
        this.audit = audit;
    }

    // ---- overview ----------------------------------------------------------------------------------

    public Overview overview(UUID projectId) {
        projects.requireAccess(projectId, false, null);
        Optional<VersionRow> topics = reviews.approved(projectId, Kind.TOPICS);
        Map<String, List<String>> decks = new HashMap<>();
        for (ResourceRow r : reviewStore.resources(projectId, Kind.SLIDES)) {
            if (reviewStore.approved(r.id()).isPresent()) decks.computeIfAbsent(r.topicId(), k -> new ArrayList<>()).add(r.level());
        }
        Map<String, Boolean> mcq = approvedByTopic(projectId, Kind.MCQ), seo = approvedByTopic(projectId, Kind.SEO);
        Map<String, List<VideoInfo>> videos = new HashMap<>();
        Map<String, JobRow> latestJob = new HashMap<>();
        for (JobRow j : jobs.recent(projectId, "NARRATION")) latestJob.putIfAbsent(j.target(), j);
        for (Kind k : List.of(Kind.LESSON_VIDEO, Kind.QUIZ_VIDEO)) {
            for (ResourceRow r : reviewStore.resources(projectId, k)) {
                VersionRow v = newest(r);
                String render = null;
                if (v != null) {
                    VersionRow full = reviewStore.version(v.id()).orElseThrow();
                    render = store.renders(r.id()).stream().filter(x -> x.contentHash().equals(full.contentHash())).findFirst()
                            .map(x -> "COMPLETED".equals(x.status()) && x.validation() != null && x.validation().path("ok").asBoolean() ? "CHECKED" : x.status())
                            .orElse(null);
                }
                JobRow j = latestJob.get(r.id().toString());
                videos.computeIfAbsent(r.topicId(), x -> new ArrayList<>()).add(new VideoInfo(k == Kind.LESSON_VIDEO ? "LESSON" : "QUIZ", r.level(), r.id(),
                        v == null ? null : v.versionNo(), v == null ? null : v.status().name(), render, running(j), failed(j)));
            }
        }
        List<TopicVideos> out = new ArrayList<>();
        for (MaterialsService.TopicRow t : topics.map(v -> MaterialsService.topicRows(MaterialsService.read(v.content()))).orElse(List.of())) {
            List<String> levels = new ArrayList<>(decks.getOrDefault(t.id(), List.of()));
            levels.sort((a, b) -> Learning.LEVELS.indexOf(a) - Learning.LEVELS.indexOf(b));
            out.add(new TopicVideos(t.id(), t.title(), t.depth(), levels, mcq.getOrDefault(t.id(), false), seo.getOrDefault(t.id(), false),
                    videos.getOrDefault(t.id(), List.of())));
        }
        return new Overview(topics.isPresent(), out, projects.canEdit(projectId), renders.available());
    }

    // ---- planning ----------------------------------------------------------------------------------

    /** Plans a video from approved content, as a draft. Returns the video's resource id. */
    public UUID create(UUID projectId, CreateRequest req) {
        projects.requireAccess(projectId, true, "Only the project's owners and editors make videos");
        VersionRow topicsV = reviews.approved(projectId, Kind.TOPICS).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Approve the topic list first."));
        materials.brief(projectId, MaterialsService.read(topicsV.content()), req.topicId());
        String language = req.language() == null ? "EN" : req.language();
        String aspect = req.aspect() == null ? ("QUIZ".equals(req.kind()) ? "9:16" : "16:9") : req.aspect();
        Voice voice = voice(req.voice());
        Optional<UUID> seoV = store.approved(projectId, Kind.SEO, req.topicId(), null);
        String seoTitle = null;
        List<String> keywords = new ArrayList<>();
        if (seoV.isPresent()) {
            JsonNode seo = MaterialsService.read(store.content(seoV.get()).orElseThrow());
            seoTitle = SeoReportRules.chosenText(seo);
            seo.path("primaryKeywords").forEach(k -> keywords.add(k.asText()));
        }
        Plan plan;
        Kind kind;
        String level = null;
        List<UUID> deps = new ArrayList<>();
        UUID dependsOn;
        if ("LESSON".equals(req.kind())) {
            kind = Kind.LESSON_VIDEO;
            level = req.level() == null ? null : req.level().strip().toUpperCase(Locale.ROOT);
            if (level == null || !Learning.LEVELS.contains(level)) throw ApiException.badRequest("Choose the notes level of the presentation");
            String lv = level;
            UUID deckV = store.approved(projectId, Kind.SLIDES, req.topicId(), level).orElseThrow(() ->
                    new ApiException(ErrorCode.CONFLICT, "A lesson video is made after its presentation is approved. Approve the "
                            + Learning.levelLabel(lv).toLowerCase(Locale.ROOT) + " presentation for this topic first."));
            plan = VideoPlans.lesson(SlidesRules.read(MaterialsService.read(store.content(deckV).orElseThrow())), deckV, seoV.orElse(null), null,
                    keywords, language, aspect, voice);
            dependsOn = deckV;
            reviewStore.resource(projectId, Kind.SLIDES, req.topicId(), level).ifPresent(r -> deps.add(r.id()));
        } else {
            kind = Kind.QUIZ_VIDEO;
            Optional<UUID> bankV = store.approved(projectId, Kind.MCQ, req.topicId(), null);
            if (bankV.isEmpty() || seoV.isEmpty()) {
                throw new ApiException(ErrorCode.CONFLICT, "A quiz video is made after the topic's MCQ set and SEO report are approved. Approve "
                        + (bankV.isEmpty() && seoV.isEmpty() ? "both" : bankV.isEmpty() ? "the MCQ set" : "the SEO report") + " first.");
            }
            Learning.Bank bank = McqRules.read(MaterialsService.read(store.content(bankV.get()).orElseThrow()));
            plan = VideoPlans.quiz(bank, bankV.get(), seoV.get(), seoTitle, language, aspect, voice, req.countdown() == null ? 5 : req.countdown(),
                    req.questions() == null ? 10 : req.questions());
            if (plan.scenes().stream().noneMatch(s -> "QUESTION".equals(s.kind()))) throw ApiException.badRequest("The MCQ set has no complete questions");
            dependsOn = bankV.get();
            for (Kind k : List.of(Kind.MCQ, Kind.SEO)) reviewStore.resource(projectId, k, req.topicId(), null).ifPresent(r -> deps.add(r.id()));
        }
        String title = plan.topicTitle() + (kind == Kind.LESSON_VIDEO ? ": " + Learning.levelLabel(level).toLowerCase(Locale.ROOT) + " lesson video" : ": quiz video");
        Target t = new Target(projectId, kind, req.topicId(), level, title, deps);
        JsonNode content = JSON.valueToTree(plan);
        String note = kind == Kind.LESSON_VIDEO ? "Planned from the approved presentation" : "Planned from the approved MCQ set";
        Optional<ResourceRow> existing = reviewStore.resource(projectId, kind, req.topicId(), level);
        if (existing.isPresent()) {
            VersionRow newest = newest(existing.get());
            if (newest != null && newest.status() == Status.IN_REVIEW) throw new ApiException(ErrorCode.CONFLICT, "This video is in review. Withdraw it first.");
            if (newest != null && newest.status() == Status.DRAFT) reviews.replaceDraft(newest, content, note);
            else reviews.createVersion(t, content, Origin.MANUAL, newest == null ? null : newest.id(), dependsOn, note);
            audit.record("VIDEO_PLANNED", "resource", existing.get().id(), Map.of("kind", req.kind()));
            return existing.get().id();
        }
        reviews.createVersion(t, content, Origin.MANUAL, null, dependsOn, note);
        UUID id = reviewStore.resource(projectId, kind, req.topicId(), level).orElseThrow().id();
        audit.record("VIDEO_PLANNED", "resource", id, Map.of("kind", req.kind()));
        return id;
    }

    static Voice voice(VoiceInput v) {
        if (v == null) return null;
        if (v.profileId() == null && (v.accountId() == null || v.voiceId() == null || v.voiceId().isBlank())) return null;
        return new Voice(v.profileId(), v.profileId() == null ? v.accountId() : null, v.profileId() == null ? v.voiceId().strip() : null,
                v.model() == null || v.model().isBlank() ? null : v.model().strip(), v.speed(), v.label());
    }

    // ---- narration ---------------------------------------------------------------------------------

    public JobRow narrate(UUID resourceId, NarrateRequest req) {
        ResourceRow r = video(resourceId);
        projects.requireAccess(r.projectId(), true, "Only the project's owners and editors narrate videos");
        VersionRow v = reviewStore.version(req.versionId()).filter(x -> x.resourceId().equals(resourceId)).orElseThrow(() -> ApiException.notFound("Version"));
        if (v.status() != Status.DRAFT) throw new ApiException(ErrorCode.CONFLICT, "Only a draft can be narrated. Start a new draft first.");
        Plan p = VideoRules.read(MaterialsService.read(v.content()));
        if (p.voice() == null) throw ApiException.badRequest("Choose a voice first");
        if (VideoPlans.spoken(p).isEmpty()) throw ApiException.badRequest("Nothing to narrate");
        UUID id = UUID.randomUUID();
        try {
            jobs.insert(id, r.projectId(), "NARRATION", resourceId.toString(), write(Map.of("versionId", v.id().toString(), "scriptHash", VideoPlans.scriptHash(p))));
        } catch (RuntimeException e) {
            if (SqlStates.find(e).map(x -> "23505".equals(x.getSQLState())).orElse(false)) {
                throw new ApiException(ErrorCode.CONFLICT, "This video's narration is already being made.");
            }
            throw e;
        }
        audit.record("NARRATION_STARTED", "resource", resourceId, Map.of("characters", VideoPlans.characters(p)));
        return jobs.job(id).orElseThrow();
    }

    @Override
    public boolean handles(String kind) {
        return kind.equals("NARRATION");
    }

    @Override
    public void process(UUID jobId) {
        JobRow j = jobs.job(jobId).orElse(null);
        if (j == null || !(j.status().equals("QUEUED") || j.status().equals("RUNNING"))) return;
        jobs.running(jobId);
        try {
            JsonNode in = JSON.readTree(j.input());
            jobs.summary(jobId, narrateNow(UUID.fromString(in.path("versionId").asText()), in.path("scriptHash").asText()));
            jobs.succeeded(jobId, null);
        } catch (ProviderException e) {
            if (e.kind().retryable() && j.attempts() + 1 < 2) jobs.retryLater(jobId, e.getMessage() + " — trying again shortly", 120);
            else jobs.failed(jobId, e.getMessage());
        } catch (ApiException e) {
            jobs.failed(jobId, e.getMessage());
        } catch (Exception e) {
            log.error("Narration job {} failed", jobId, e);
            jobs.failed(jobId, "Making the narration failed unexpectedly");
        }
    }

    String narrateNow(UUID versionId, String scriptHash) {
        VersionRow v = reviewStore.version(versionId).orElseThrow(() -> ApiException.notFound("Version"));
        if (v.status() != Status.DRAFT) throw new ApiException(ErrorCode.CONFLICT, "The draft was sent for review or replaced before it was narrated");
        Plan p = VideoRules.read(MaterialsService.read(v.content()));
        if (!scriptHash.equals(VideoPlans.scriptHash(p))) throw new ApiException(ErrorCode.CONFLICT, "The words or the voice changed. Make the narration again.");
        List<VideoPlans.Spoken> parts = VideoPlans.spoken(p);
        Voice vc = p.voice();
        VoiceService.SceneNarration n = voices.narrateScenes(new VoiceChoice(vc.profileId(), vc.accountId(), vc.voiceId(), vc.model()), p.language(),
                vc.speed() == null ? null : new VoiceStyle(null, null, vc.speed(), null, null, null), parts.stream().map(VideoPlans.Spoken::text).toList(),
                NARRATION_GAP, p.title() == null ? "Narration" : p.title() + " (narration)", reviewStore.resource(v.resourceId()).orElseThrow().projectId());
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            double[] at = n.parts().get(i);
            segments.add(new Segment(parts.get(i).sceneId(), parts.get(i).part(), at[0], at[1]));
        }
        Narration narration = new Narration(n.media().getId(), scriptHash, segments, VideoPlans.characters(p), vc.label(), Instant.now().toString());
        Plan withNarration = new Plan(p.kind(), p.topicId(), p.topicTitle(), p.level(), p.sourceVersionId(), p.seoVersionId(), p.title(), p.language(),
                p.style(), p.aspect(), p.voice(), p.quiz(), p.scenes(), narration, p.captions());
        VersionRow now = reviewStore.version(versionId).orElseThrow();
        if (now.status() != Status.DRAFT || !scriptHash.equals(VideoPlans.scriptHash(VideoRules.read(MaterialsService.read(now.content()))))) {
            throw new ApiException(ErrorCode.CONFLICT, "The draft changed while the narration was made. Make it again.");
        }
        reviews.replaceDraft(now, JSON.valueToTree(withNarration), "Narration made (" + parts.size() + " parts, " + narration.characters() + " characters)");
        return "Narrated " + parts.size() + " parts, " + narration.characters() + " characters.";
    }

    // ---- rendering ---------------------------------------------------------------------------------

    public RenderInfo render(UUID resourceId, StartRender req) {
        ResourceRow r = video(resourceId);
        projects.requireAccess(r.projectId(), true, "Only the project's owners and editors render videos");
        VersionRow v = reviewStore.version(req.versionId()).filter(x -> x.resourceId().equals(resourceId)).orElseThrow(() -> ApiException.notFound("Version"));
        if (v.status() != Status.DRAFT && v.status() != Status.IN_REVIEW && v.status() != Status.CHANGES_REQUESTED) {
            throw new ApiException(ErrorCode.CONFLICT, "Only a draft or a version in review is rendered for approval");
        }
        Plan p = VideoRules.read(MaterialsService.read(v.content()));
        if (p.narration() == null) throw new ApiException(ErrorCode.CONFLICT, "Make the narration first");
        Map<String, Object> doc = VideoTimeline.build(p, f -> store.figure(r.projectId(), f.kind(), f.source()).orElse(null));
        String name = (p.title() == null ? r.title() : p.title()) + " (v" + v.versionNo() + ")";
        if (name.length() > 160) name = name.substring(0, 160);
        UUID timelineId = store.renders(resourceId).stream().map(VideoStore.RenderRow::timelineId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (timelineId != null) {
            try {
                var t = timelines.get(timelineId);
                timelines.saveDocument(timelineId, new SaveDocumentRequest(t.revision(), doc));
            } catch (ApiException e) {
                timelineId = null;
            }
        }
        if (timelineId == null) timelineId = timelines.create(new CreateTimelineRequest(name, doc, r.projectId())).id();
        RenderJobResponse job = renders.start(timelineId, new RenderRequest(req.resolution(), req.quality() == null ? RenderSettings.Quality.HIGH : req.quality()));
        store.recordRender(r.projectId(), resourceId, v.id(), v.contentHash(), job.id(), timelineId);
        audit.record("VIDEO_RENDER_STARTED", "resource", resourceId, Map.of("version", v.versionNo(), "resolution", req.resolution()));
        return info(store.renders(resourceId).get(0), v.contentHash());
    }

    public List<RenderInfo> renders(UUID resourceId, UUID versionId) {
        ResourceRow r = video(resourceId);
        projects.requireAccess(r.projectId(), false, null);
        String hash = versionId == null ? null : reviewStore.version(versionId).filter(x -> x.resourceId().equals(resourceId)).map(VersionRow::contentHash).orElse(null);
        return store.renders(resourceId).stream().map(x -> info(x, hash)).toList();
    }

    private RenderInfo info(VideoStore.RenderRow x, String hash) {
        Integer no = reviewStore.version(x.versionId()).map(VersionRow::versionNo).orElse(null);
        return new RenderInfo(x.renderJobId(), x.versionId(), no, x.contentHash().equals(hash), x.status(), x.progress(), x.error(),
                x.outputMediaId() == null ? null : media.findResponse(x.outputMediaId()).orElse(null), x.validation(), x.timelineId(), x.createdAt(), x.completedAt());
    }

    /** The timeline a plan makes, without rendering (for the preview). */
    public Map<String, Object> preview(UUID resourceId, UUID versionId) {
        ResourceRow r = video(resourceId);
        projects.requireAccess(r.projectId(), false, null);
        VersionRow v = reviewStore.version(versionId).filter(x -> x.resourceId().equals(resourceId)).orElseThrow(() -> ApiException.notFound("Version"));
        Plan p = VideoRules.read(MaterialsService.read(v.content()));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> doc = VideoTimeline.build(p, f -> store.figure(r.projectId(), f.kind(), f.source()).orElse(null));
        out.put("document", doc);
        out.put("durationSeconds", VideoTimeline.duration(doc));
        Map<String, Object> mediaRefs = new LinkedHashMap<>();
        media.findResponse(p.narration().mediaId()).ifPresent(m -> mediaRefs.put(p.narration().mediaId().toString(), m));
        for (var s : Videos.list(p.scenes())) {
            if (s.slide() != null && s.slide().image() != null) {
                media.findResponse(s.slide().image().assetId()).ifPresent(m -> mediaRefs.put(s.slide().image().assetId().toString(), m));
            }
        }
        out.put("media", mediaRefs);
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private ResourceRow video(UUID resourceId) {
        return reviewStore.resource(resourceId).filter(x -> x.kind() == Kind.LESSON_VIDEO || x.kind() == Kind.QUIZ_VIDEO)
                .orElseThrow(() -> ApiException.notFound("Video"));
    }

    private Map<String, Boolean> approvedByTopic(UUID projectId, Kind kind) {
        Map<String, Boolean> out = new HashMap<>();
        for (ResourceRow r : reviewStore.resources(projectId, kind)) if (reviewStore.approved(r.id()).isPresent()) out.put(r.topicId(), true);
        return out;
    }

    private VersionRow newest(ResourceRow r) {
        List<VersionRow> vs = reviewStore.versions(r.id());
        return vs.isEmpty() ? null : vs.get(0);
    }

    private static boolean running(JobRow j) {
        return j != null && (j.status().equals("QUEUED") || j.status().equals("RUNNING"));
    }

    private static String failed(JobRow j) {
        return j != null && j.status().equals("FAILED") ? j.error() : null;
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
