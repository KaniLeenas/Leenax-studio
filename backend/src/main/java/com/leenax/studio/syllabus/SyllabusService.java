package com.leenax.studio.syllabus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.text.TextAi;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.Review.Origin;
import com.leenax.studio.approvals.Review.Status;
import com.leenax.studio.approvals.ReviewDtos.ResourceState;
import com.leenax.studio.approvals.ReviewService;
import com.leenax.studio.approvals.ReviewStore;
import com.leenax.studio.approvals.ReviewStore.ResourceRow;
import com.leenax.studio.approvals.ReviewStore.VersionRow;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.common.SqlStates;
import com.leenax.studio.projects.ProjectService;
import com.leenax.studio.syllabus.AnalysisStore.JobRow;
import com.leenax.studio.syllabus.AnalysisStore.ReadyDoc;
import com.leenax.studio.syllabus.Content.Syllabus;
import com.leenax.studio.syllabus.Content.Topics;
import com.leenax.studio.syllabus.SyllabusAnalysis.SourceDoc;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The syllabus report and the topic list (E3). The report is written by your own AI text
 * account from the project's course documents, as a draft for people to check; topics are
 * built only from an approved syllabus.
 */
@Service
public class SyllabusService {

    private static final Logger log = LoggerFactory.getLogger(SyllabusService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_DOCUMENTS = 10;
    static final int MAX_ATTEMPTS = 2;

    public record GenerateRequest(@NotEmpty @Size(max = MAX_DOCUMENTS) List<UUID> documentIds, @NotNull UUID accountId,
                                  @NotNull @Size(min = 1, max = 120) String model, @Size(max = 1000) String guidance) {
    }

    public record DocumentChoice(UUID documentId, String title, UUID versionId, int versionNo, int pages, long chars) {
    }

    public record JobResponse(UUID id, String status, List<String> documents, String model, UUID resultVersionId, String error,
                              int attempts, Instant createdAt, Instant finishedAt, String startedBy) {
    }

    /** What the syllabus page needs besides the versions: documents to choose from, and the last analysis. */
    public record Overview(List<DocumentChoice> documents, long maxChars, JobResponse job) {
    }

    private final AnalysisStore store;
    private final ReviewService reviews;
    private final ReviewStore reviewStore;
    private final ProjectService projects;
    private final TextAi textAi;
    private final AuditService audit;
    private final TopicRules topicRules;
    private final long maxChars;

    public SyllabusService(AnalysisStore store, ReviewService reviews, ReviewStore reviewStore, ProjectService projects, TextAi textAi,
                           AuditService audit, TopicRules topicRules,
                           @Value("${leenax.syllabus.max-source-chars:150000}") long maxChars) {
        this.store = store;
        this.reviews = reviews;
        this.reviewStore = reviewStore;
        this.projects = projects;
        this.textAi = textAi;
        this.audit = audit;
        this.topicRules = topicRules;
        this.maxChars = maxChars;
    }

    // ---- syllabus ----------------------------------------------------------------------------------

    public Overview overview(UUID projectId) {
        projects.requireAccess(projectId, false, null);
        List<DocumentChoice> docs = store.readyDocuments(projectId).stream()
                .map(d -> new DocumentChoice(d.documentId(), d.title(), d.versionId(), d.versionNo(), d.pages(), d.chars()))
                .sorted((a, b) -> a.title().compareToIgnoreCase(b.title())).toList();
        return new Overview(docs, maxChars, store.latest(projectId).map(SyllabusService::job).orElse(null));
    }

    /** Queues the AI analysis of the chosen documents. */
    public JobResponse generate(UUID projectId, GenerateRequest req) {
        projects.requireAccess(projectId, true, "Only the project's owners and editors can analyse the syllabus");
        Map<UUID, ReadyDoc> ready = new LinkedHashMap<>();
        store.readyDocuments(projectId).forEach(d -> ready.put(d.documentId(), d));
        List<Map<String, Object>> chosen = new ArrayList<>();
        long chars = 0;
        for (UUID id : new LinkedHashSet<>(req.documentIds())) {
            ReadyDoc d = ready.get(id);
            if (d == null) throw ApiException.badRequest("A chosen document isn't in this project or hasn't been read yet");
            chars += d.chars();
            chosen.add(Map.of("documentId", d.documentId(), "versionId", d.versionId(), "title", d.title(), "versionNo", d.versionNo()));
        }
        if (chars > maxChars) {
            throw ApiException.badRequest("The chosen documents have " + String.format("%,d", chars) + " characters of text; the limit is "
                    + String.format("%,d", maxChars) + ". Choose the module descriptor and handbook rather than every lecture.");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("documents", chosen);
        input.put("accountId", req.accountId());
        input.put("model", req.model().strip());
        if (req.guidance() != null && !req.guidance().isBlank()) input.put("guidance", req.guidance().strip());
        UUID id = UUID.randomUUID();
        try {
            store.insert(id, projectId, write(input));
        } catch (RuntimeException e) {
            if (SqlStates.find(e).map(x -> "23505".equals(x.getSQLState())).orElse(false)) {
                throw new ApiException(ErrorCode.CONFLICT, "An analysis is already running for this project. Wait for it to finish.");
            }
            throw e;
        }
        audit.record("SYLLABUS_ANALYSIS_STARTED", "project", projectId, Map.of("documents", chosen.size(), "model", req.model()));
        return store.job(id).map(SyllabusService::job).orElseThrow();
    }

    public JobResponse cancel(UUID projectId, UUID jobId) {
        projects.requireAccess(projectId, true, "Only the project's owners and editors can cancel the analysis");
        JobRow j = store.job(jobId).filter(x -> x.projectId().equals(projectId)).orElseThrow(() -> ApiException.notFound("Analysis"));
        if (store.cancel(j.id()) == 0) throw new ApiException(ErrorCode.CONFLICT, "It has already started, so it can't be cancelled now.");
        return store.job(jobId).map(SyllabusService::job).orElseThrow();
    }

    /** A blank draft, for writing the syllabus by hand. */
    public ResourceState writeByHand(UUID projectId) {
        projects.requireAccess(projectId, true, "Only the project's owners and editors can write the syllabus");
        if (reviewStore.resource(projectId, Kind.SYLLABUS).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "This project already has a syllabus. Edit its newest version instead.");
        }
        Syllabus blank = new Syllabus(null, List.of(), List.of(), List.of(), List.of(), List.of(), null);
        reviews.createVersion(projectId, Kind.SYLLABUS, Content.tree(blank), Origin.MANUAL, null, null, "Written by hand");
        return reviews.state(projectId, Kind.SYLLABUS);
    }

    /** Runs one analysis (called by the worker, as the person who started it). */
    public void process(UUID jobId) {
        JobRow j = store.job(jobId).orElse(null);
        if (j == null || !(j.status().equals("QUEUED") || j.status().equals("RUNNING"))) return;
        store.running(jobId);
        try {
            JsonNode input = JSON.readTree(j.input());
            List<SourceDoc> docs = new ArrayList<>();
            int n = 1;
            for (JsonNode d : input.path("documents")) {
                UUID versionId = UUID.fromString(d.path("versionId").asText());
                docs.add(new SourceDoc("D" + n++, UUID.fromString(d.path("documentId").asText()), versionId, d.path("title").asText(),
                        d.path("versionNo").asInt(), store.pages(versionId)));
            }
            if (docs.stream().allMatch(d -> d.pages().isEmpty())) throw ApiException.badRequest("The chosen documents have no text");
            String model = input.path("model").asText();
            JsonNode reply = textAi.askJson(UUID.fromString(input.path("accountId").asText()), model, SyllabusAnalysis.SYSTEM,
                    SyllabusAnalysis.prompt(docs, input.path("guidance").asText(null)));
            Syllabus syllabus = SyllabusAnalysis.read(reply, docs, model);
            if (syllabus.structure().isEmpty()) throw ApiException.badRequest("The AI found no syllabus structure in these documents");
            VersionRow v = reviews.createVersion(j.projectId(), Kind.SYLLABUS, Content.tree(syllabus), Origin.AI, null, null,
                    "AI analysis of " + docs.size() + (docs.size() == 1 ? " document" : " documents") + " with " + model);
            store.succeeded(jobId, v.id());
            log.info("Syllabus analysis {} made version {}", jobId, v.versionNo());
        } catch (ProviderException e) {
            int attempts = j.attempts() + 1;
            if (e.kind().retryable() && attempts < MAX_ATTEMPTS) store.retryLater(jobId, e.getMessage() + " — trying again shortly", 120);
            else store.failed(jobId, e.getMessage());
        } catch (ApiException e) {
            store.failed(jobId, e.getMessage());
        } catch (Exception e) {
            log.error("Syllabus analysis {} failed", jobId, e);
            store.failed(jobId, "The analysis failed unexpectedly");
        }
    }

    // ---- topics ------------------------------------------------------------------------------------

    /**
     * Builds the topic list from the approved syllabus, or brings it up to date with a newer
     * approved syllabus. Your edits are kept (see {@link TopicRules#rebase}).
     */
    @Transactional
    public ResourceState updateTopics(UUID projectId) {
        projects.requireAccess(projectId, true, "Only the project's owners and editors can change the topics");
        VersionRow syllabus = reviews.approved(projectId, Kind.SYLLABUS)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Approve the syllabus first. Topics are built only from an approved syllabus."));
        Syllabus content = SyllabusRules.parse(ReviewServiceAccess.read(reviewStore, syllabus.id()));
        ResourceRow topics = reviewStore.resource(projectId, Kind.TOPICS).orElse(null);
        List<VersionRow> versions = topics == null ? List.of() : reviewStore.versions(topics.id());

        if (versions.isEmpty()) {
            Topics fresh = TopicRules.rebase(null, content, syllabus.id());
            reviews.createVersion(projectId, Kind.TOPICS, Content.tree(fresh), Origin.MANUAL, null, syllabus.id(),
                    "Built from syllabus version " + syllabus.versionNo());
            return reviews.state(projectId, Kind.TOPICS);
        }
        VersionRow newest = versions.get(0);
        if (newest.status() == Status.IN_REVIEW) {
            throw new ApiException(ErrorCode.CONFLICT, "Version " + newest.versionNo() + " is in review. Withdraw it first to update it.");
        }
        Topics current = TopicRules.parse(ReviewServiceAccess.read(reviewStore, newest.id()));
        Topics rebased = TopicRules.rebase(current, content, syllabus.id());
        if (newest.status() == Status.DRAFT) {
            JsonNode clean = topicRules.normalise(Content.tree(rebased), syllabus.id());
            String canonical = Review.canonical(clean);
            if (reviewStore.saveDraft(newest.id(), canonical, Review.hash(canonical), syllabus.id()) == 0) {
                throw new ApiException(ErrorCode.CONFLICT, "This version changed meanwhile. Reload and try again.");
            }
        } else {
            reviews.createVersion(projectId, Kind.TOPICS, Content.tree(rebased), Origin.EDIT, newest.id(), syllabus.id(),
                    "Updated from syllabus version " + syllabus.versionNo());
        }
        return reviews.state(projectId, Kind.TOPICS);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    static JobResponse job(JobRow j) {
        List<String> docs = new ArrayList<>();
        String model = null;
        try {
            JsonNode in = JSON.readTree(j.input());
            in.path("documents").forEach(d -> docs.add(d.path("title").asText()));
            model = in.path("model").asText(null);
        } catch (Exception ignored) {
            // shown without details
        }
        return new JobResponse(j.id(), j.status(), docs, model, j.resultVersionId(), j.error(), j.attempts(), j.createdAt(),
                j.finishedAt(), j.createdBy());
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads a stored version's content. */
    static final class ReviewServiceAccess {
        private ReviewServiceAccess() {
        }

        static JsonNode read(ReviewStore store, UUID versionId) {
            String s = store.version(versionId).map(VersionRow::content).orElseThrow(() -> ApiException.notFound("Version"));
            try {
                return JSON.readTree(s);
            } catch (Exception e) {
                throw new IllegalStateException("Stored content isn't JSON", e);
            }
        }
    }
}
