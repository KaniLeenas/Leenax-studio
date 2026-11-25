package com.leenax.studio.topicanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.accounts.AiAccount;
import com.leenax.studio.ai.accounts.AiAccountRepository;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.text.TextAi;
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
import com.leenax.studio.notes.Learning;
import com.leenax.studio.notes.MaterialsAi.Passage;
import com.leenax.studio.notes.MaterialsAi.TopicBrief;
import com.leenax.studio.notes.MaterialsService;
import com.leenax.studio.projects.ProjectService;
import com.leenax.studio.syllabus.AnalysisStore;
import com.leenax.studio.syllabus.AnalysisStore.JobRow;
import com.leenax.studio.syllabus.AnalysisWorker;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Topic analysis (E11): runs Prompt 1 for a topic, saves the answer as a draft to approve, and
 * shows a project's topics with their analysis and what has followed from it. The automatic
 * start after an approval is done by the database (V19), so it happens whoever approves.
 */
@Service
public class TopicAnalysisService implements AnalysisWorker.Runner {

    private static final Logger log = LoggerFactory.getLogger(TopicAnalysisService.class);
    static final ObjectMapper JSON = new ObjectMapper();
    static final String KIND = "TOPIC_ANALYSIS";
    static final int MAX_RUN = 60;
    static final String EDIT = "Only the project's owners and editors start AI work";

    private final ReviewService reviews;
    private final ReviewStore reviewStore;
    private final AnalysisStore jobs;
    private final MaterialsService materials;
    private final ProjectService projects;
    private final TextAi textAi;
    private final AiAccountRepository accounts;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public TopicAnalysisService(ReviewService reviews, ReviewStore reviewStore, AnalysisStore jobs, MaterialsService materials,
                                ProjectService projects, TextAi textAi, AiAccountRepository accounts, AuditService audit, JdbcTemplate jdbc) {
        this.reviews = reviews;
        this.reviewStore = reviewStore;
        this.jobs = jobs;
        this.materials = materials;
        this.projects = projects;
        this.textAi = textAi;
        this.accounts = accounts;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---- settings --------------------------------------------------------------------------------------

    public record Settings(boolean enabled, UUID accountId, String accountName, String model, String notesLevel, int mcqCount, int videoMinutes,
                           boolean autoNotes, boolean autoMcq, boolean autoSlides, String updatedByName, Instant updatedAt) {
    }

    public record SettingsRequest(boolean enabled, UUID accountId, @Size(max = 100) String model, String notesLevel,
                                  @Min(4) @Max(30) int mcqCount, @Min(3) @Max(20) int videoMinutes, boolean autoNotes, boolean autoMcq,
                                  boolean autoSlides) {
    }

    static final String SETTINGS = """
            select a.enabled, a.account_id, ac.name, a.model, a.notes_level, a.mcq_count, a.video_minutes, a.auto_notes, a.auto_mcq,
                   a.auto_slides, u.display_name, a.updated_at
              from topic_automation a left join ai_accounts ac on ac.id = a.account_id left join users u on u.id = a.updated_by
             where a.project_id = ?
            """;

    static final String SAVE = """
            insert into topic_automation (project_id, enabled, account_id, model, notes_level, mcq_count, video_minutes, auto_notes, auto_mcq,
                                          auto_slides, updated_by, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, leenax_uid(), now())
            on conflict (project_id) do update set enabled = excluded.enabled, account_id = excluded.account_id, model = excluded.model,
                notes_level = excluded.notes_level, mcq_count = excluded.mcq_count, video_minutes = excluded.video_minutes,
                auto_notes = excluded.auto_notes, auto_mcq = excluded.auto_mcq, auto_slides = excluded.auto_slides,
                updated_by = leenax_uid(), updated_at = now()
            """;

    public Settings settings(UUID projectId) {
        return jdbc.query(SETTINGS, (rs, i) -> new Settings(rs.getBoolean(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getBoolean(8), rs.getBoolean(9), rs.getBoolean(10), rs.getString(11),
                rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant()), projectId).stream().findFirst()
                .orElse(new Settings(false, null, null, null, "INTERMEDIATE", 20, 8, true, true, true, null, null));
    }

    @Transactional
    public Settings saveSettings(UUID projectId, SettingsRequest req) {
        projects.requireAccess(projectId, true, EDIT);
        String level = req.notesLevel() == null ? "INTERMEDIATE" : req.notesLevel().strip().toUpperCase(java.util.Locale.ROOT);
        if (!Learning.LEVELS.contains(level)) throw ApiException.badRequest("Choose a notes level");
        String model = req.model() == null || req.model().isBlank() ? null : req.model().strip();
        if (req.enabled()) {
            if (req.accountId() == null || model == null) throw ApiException.badRequest("Choose the AI account and model the automatic work uses");
            AiAccount a = accounts.findById(req.accountId()).orElseThrow(() -> ApiException.badRequest("That AI account doesn't exist"));
            if (!a.isUsable()) throw ApiException.badRequest("That AI account isn't working (" + a.getStatus().name().toLowerCase(java.util.Locale.ROOT) + "). Check it under AI accounts.");
        }
        try {
            jdbc.update(SAVE, projectId, req.enabled(), req.accountId(), model, level, req.mcqCount(), req.videoMinutes(), req.autoNotes(), req.autoMcq(),
                    req.autoSlides());
        } catch (RuntimeException e) {
            throw SqlStates.translate(e).orElseThrow(() -> e);
        }
        audit.record("TOPIC_AUTOMATION_SAVED", "project", projectId, Map.of("enabled", req.enabled(), "model", model == null ? "" : model,
                "questions", req.mcqCount(), "minutes", req.videoMinutes()));
        return settings(projectId);
    }

    // ---- the project's topics -------------------------------------------------------------------------------

    public record Step(String kind, String label, String status, UUID resourceId, String job, String jobError, boolean auto) {
    }

    public record TopicRow(String topicId, String title, String path, UUID resourceId, String status, Integer versionNo, Integer approvedNo,
                           String job, String jobError, boolean jobAuto, Instant jobAt, List<Step> next) {
    }

    public record Overview(boolean topicsApproved, Integer topicsVersionNo, Settings settings, List<TopicRow> topics, boolean canEdit,
                           boolean canReview, Map<String, Integer> counts) {
    }

    public Overview overview(UUID projectId) {
        var p = projects.requireAccess(projectId, false, null);
        Optional<VersionRow> topics = reviews.approved(projectId, Kind.TOPICS);
        Settings s = settings(projectId);
        List<TopicRow> rows = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (topics.isPresent()) {
            JsonNode t = MaterialsService.read(topics.get().content());
            Map<String, JobRow> latestJobs = latestJobs(projectId);
            for (String[] u : units(t)) {
                String id = u[0];
                Optional<ResourceRow> r = reviewStore.resource(projectId, Kind.TOPIC_ANALYSIS, id, null);
                VersionRow newest = r.map(x -> reviewStore.versions(x.id())).filter(v -> !v.isEmpty()).map(v -> v.get(0)).orElse(null);
                Integer approvedNo = r.flatMap(x -> reviewStore.approved(x.id())).map(VersionRow::versionNo).orElse(null);
                JobRow j = latestJobs.get(KIND + "|" + id);
                String status = newest == null ? "NONE" : newest.status().name();
                counts.merge(approvedNo != null ? "APPROVED" : status, 1, Integer::sum);
                rows.add(new TopicRow(id, u[1], u[2], r.map(ResourceRow::id).orElse(null), status, newest == null ? null : newest.versionNo(),
                        approvedNo, j == null ? null : j.status(), j == null ? null : j.error(), j != null && auto(j.id()), j == null ? null : j.createdAt(),
                        approvedNo == null ? List.of() : next(projectId, id, s.notesLevel(), latestJobs)));
            }
        }
        return new Overview(topics.isPresent(), topics.map(VersionRow::versionNo).orElse(null), s, rows, projects.canEdit(projectId),
                projects.canReview(projectId), counts);
    }

    /** What has followed an approved analysis for a topic: notes, questions, QA, SEO, presentation, lesson video. */
    public List<Step> next(UUID projectId, String topicId, String level, Map<String, JobRow> latestJobs) {
        List<Step> out = new ArrayList<>();
        out.add(step(projectId, Kind.NOTES, topicId, level, Learning.levelLabel(level) + " notes", latestJobs.get("NOTES|" + topicId + ":" + level)));
        out.add(step(projectId, Kind.MCQ, topicId, null, "Questions", latestJobs.get("MCQ|" + topicId)));
        out.add(step(projectId, Kind.QA, topicId, null, "Academic QA report", null));
        out.add(step(projectId, Kind.SEO, topicId, null, "SEO report", latestJobs.get("SEO|" + topicId)));
        out.add(step(projectId, Kind.SLIDES, topicId, level, "Presentation (the video's script)", latestJobs.get("SLIDES|" + topicId + ":" + level)));
        out.add(step(projectId, Kind.LESSON_VIDEO, topicId, level, "Lesson video", null));
        return out;
    }

    private Step step(UUID projectId, Kind kind, String topicId, String level, String label, JobRow job) {
        Optional<ResourceRow> r = reviewStore.resource(projectId, kind, topicId, level);
        String status = "NONE";
        if (r.isPresent()) {
            if (reviewStore.approved(r.get().id()).isPresent()) status = "APPROVED";
            else {
                List<VersionRow> vs = reviewStore.versions(r.get().id());
                status = vs.isEmpty() ? "NONE" : vs.get(0).status().name();
            }
        }
        boolean live = job != null && (job.status().equals("QUEUED") || job.status().equals("RUNNING"));
        return new Step(kind.name(), label, status, r.map(ResourceRow::id).orElse(null), live || (job != null && job.status().equals("FAILED")) ? job.status() : null,
                job != null && job.status().equals("FAILED") ? job.error() : null, job != null && auto(job.id()));
    }

    public record Next(UUID projectId, String topicId, String topicTitle, String notesLevel, List<Step> steps, Settings settings) {
    }

    public Next next(UUID resourceId) {
        ResourceRow r = reviewStore.resource(resourceId).filter(x -> x.kind() == Kind.TOPIC_ANALYSIS).orElseThrow(() -> ApiException.notFound("Topic analysis"));
        projects.requireAccess(r.projectId(), false, null);
        Settings s = settings(r.projectId());
        return new Next(r.projectId(), r.topicId(), r.title(), s.notesLevel(), next(r.projectId(), r.topicId(), s.notesLevel(), latestJobs(r.projectId())), s);
    }

    private Map<String, JobRow> latestJobs(UUID projectId) {
        Map<String, JobRow> out = new HashMap<>();
        for (String kind : List.of(KIND, "NOTES", "MCQ", "SEO", "SLIDES")) {
            for (JobRow j : jobs.recent(projectId, kind)) out.putIfAbsent(kind + "|" + j.target(), j);
        }
        return out;
    }

    private boolean auto(UUID jobId) {
        Boolean a = jdbc.queryForObject("select auto from analysis_jobs where id = ?", Boolean.class, jobId);
        return Boolean.TRUE.equals(a);
    }

    /**
     * The teaching units of a topic list, as the database chooses them (V19): each selected topic
     * under a chapter, and each selected chapter without selected topics. {id, title, path}.
     */
    static List<String[]> units(JsonNode topics) {
        List<String[]> out = new ArrayList<>();
        for (JsonNode ch : topics.path("nodes")) {
            if (!ch.path("selected").asBoolean(true)) continue;
            boolean any = false;
            for (JsonNode c : ch.path("children")) {
                if (!c.path("selected").asBoolean(true)) continue;
                any = true;
                out.add(new String[]{c.path("id").asText(), c.path("title").asText(), ch.path("title").asText()});
            }
            if (!any) out.add(new String[]{ch.path("id").asText(), ch.path("title").asText(), null});
        }
        return out;
    }

    // ---- running it -----------------------------------------------------------------------------------------

    public record RunRequest(List<String> topicIds, UUID accountId, @Size(max = 100) String model, @Size(max = 1000) String guidance) {
    }

    public record Started(int queued, int skipped, List<String> notes) {
    }

    /** Analyses topics now: the ones named, or every topic without an analysis. */
    public Started run(UUID projectId, RunRequest req) {
        projects.requireAccess(projectId, true, EDIT);
        VersionRow topics = reviews.approved(projectId, Kind.TOPICS).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Approve the topic list first."));
        Settings s = settings(projectId);
        UUID account = req.accountId() != null ? req.accountId() : s.accountId();
        String model = req.model() != null && !req.model().isBlank() ? req.model().strip() : s.model();
        if (account == null || model == null) throw ApiException.badRequest("Choose the AI account and model");
        List<String[]> units = units(MaterialsService.read(topics.content()));
        List<String> wanted = req.topicIds() == null || req.topicIds().isEmpty() ? null : req.topicIds();
        int queued = 0, skipped = 0;
        List<String> notes = new ArrayList<>();
        for (String[] u : units) {
            if (wanted != null && !wanted.contains(u[0])) continue;
            Optional<ResourceRow> existing = reviewStore.resource(projectId, Kind.TOPIC_ANALYSIS, u[0], null);
            if (wanted == null && existing.isPresent()) continue;
            VersionRow newest = existing.map(r -> reviewStore.versions(r.id())).filter(v -> !v.isEmpty()).map(v -> v.get(0)).orElse(null);
            if (newest != null && newest.status() == Status.IN_REVIEW) {
                skipped++;
                notes.add("“" + u[1] + "” is in review");
                continue;
            }
            if (queued >= MAX_RUN) {
                skipped++;
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("topicId", u[0]);
            input.put("accountId", account);
            input.put("model", model);
            input.put("mcqCount", s.mcqCount());
            input.put("videoMinutes", s.videoMinutes());
            input.put("level", s.notesLevel());
            if (req.guidance() != null && !req.guidance().isBlank()) input.put("guidance", req.guidance().strip());
            try {
                jobs.insert(UUID.randomUUID(), projectId, KIND, u[0], JSON.writeValueAsString(input));
                queued++;
            } catch (RuntimeException e) {
                if (SqlStates.find(e).map(x -> "23505".equals(x.getSQLState())).orElse(false)) {
                    skipped++;
                    notes.add("“" + u[1] + "” is already being analysed");
                } else throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        if (wanted != null && queued + skipped == 0) throw ApiException.badRequest("Those topics aren't in the approved topic list");
        audit.record("TOPIC_ANALYSIS_STARTED", "project", projectId, Map.of("topics", queued, "model", model));
        return new Started(queued, skipped, notes);
    }

    @Override
    public boolean handles(String kind) {
        return KIND.equals(kind);
    }

    @Override
    public void process(UUID jobId) {
        JobRow j = jobs.job(jobId).orElse(null);
        if (j == null || !(j.status().equals("QUEUED") || j.status().equals("RUNNING"))) return;
        jobs.running(jobId);
        try {
            VersionRow made = write(j, JSON.readTree(j.input()));
            jobs.succeeded(jobId, made.id());
        } catch (ProviderException e) {
            if (e.kind().retryable() && j.attempts() + 1 < 2) jobs.retryLater(jobId, e.getMessage() + " — trying again shortly", 120);
            else jobs.failed(jobId, e.getMessage());
        } catch (ApiException e) {
            jobs.failed(jobId, e.getMessage());
        } catch (Exception e) {
            log.error("Topic analysis {} failed", jobId, e);
            jobs.failed(jobId, "The analysis failed unexpectedly");
        }
    }

    private VersionRow write(JobRow j, JsonNode in) {
        String topicId = in.path("topicId").asText(), model = in.path("model").asText();
        int mcqCount = Math.max(4, Math.min(30, in.path("mcqCount").asInt(20)));
        int minutes = Math.max(3, Math.min(20, in.path("videoMinutes").asInt(8)));
        String level = in.path("level").asText("INTERMEDIATE");
        VersionRow topics = reviews.approved(j.projectId(), Kind.TOPICS).orElseThrow(() -> ApiException.badRequest("The topic list isn't approved any more"));
        JsonNode t = MaterialsService.read(topics.content());
        TopicBrief brief = materials.brief(j.projectId(), t, topicId);
        List<Passage> passages = materials.passages(j.projectId(), t, topicId, brief.title());
        JsonNode reply = textAi.askJson(UUID.fromString(in.path("accountId").asText()), model, TopicAnalysisAi.SYSTEM,
                TopicAnalysisAi.prompt(brief, passages, mcqCount, minutes, level, in.path("guidance").asText(null)));
        TopicAnalysis raw = TopicAnalysisAi.read(reply, brief, topics.id(), passages, mcqCount, minutes, model);
        List<String> tidied = new ArrayList<>();
        TopicAnalysis a = TopicAnalysisRules.tidy(raw, tidied);
        if (a.objectives().isEmpty() && a.concepts().isEmpty()) throw ApiException.badRequest("The AI wrote no objectives or concepts. Try again or use another model.");
        JsonNode content = JSON.valueToTree(a);
        var made = a.analysis();
        jobs.summary(j.id(), a.objectives().size() + " objectives, " + a.concepts().size() + " concepts, "
                + (a.videoPlan() == null ? 0 : a.videoPlan().scenes().size()) + " scenes; " + made.quotesFound() + " of " + made.quotesKept()
                + " quotes found on their page" + (made.quotesRemoved() > 0 ? ", " + made.quotesRemoved() + " citations of pages it wasn't given removed" : "")
                + (tidied.isEmpty() ? "" : "; " + String.join("; ", tidied.stream().distinct().toList())));
        Optional<ResourceRow> existing = reviewStore.resource(j.projectId(), Kind.TOPIC_ANALYSIS, topicId, null);
        String note = "Analysed by the AI with " + model + (in.path("guidance").isTextual() ? ", following guidance" : "");
        if (existing.isPresent()) {
            VersionRow newest = reviewStore.versions(existing.get().id()).get(0);
            if (newest.status() == Status.DRAFT && topics.id().equals(newest.dependsOn())) return reviews.replaceDraft(newest, content, note);
            return reviews.createVersion(target(j.projectId(), brief), content, Origin.AI, newest.id(), topics.id(), note);
        }
        return reviews.createVersion(target(j.projectId(), brief), content, Origin.AI, null, topics.id(), note);
    }

    private Target target(UUID projectId, TopicBrief t) {
        List<UUID> links = reviewStore.resource(projectId, Kind.TOPICS).map(r -> List.of(r.id())).orElse(List.of());
        return new Target(projectId, Kind.TOPIC_ANALYSIS, t.id(), null, t.title() + ": topic analysis", links);
    }

    /** The approved analysis of a topic, for the question and presentation prompts. */
    public static Optional<TopicAnalysis> approved(ReviewStore store, UUID projectId, String topicId) {
        return store.resource(projectId, Kind.TOPIC_ANALYSIS, topicId, null).flatMap(r -> store.approved(r.id()))
                .map(v -> JSON.convertValue(MaterialsService.read(v.content()), TopicAnalysis.class));
    }
}
