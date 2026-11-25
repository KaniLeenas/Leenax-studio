package com.leenax.studio.notes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.text.TextAi;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.Review.Origin;
import com.leenax.studio.approvals.Review.Status;
import com.leenax.studio.approvals.ReviewDtos.ResourceState;
import com.leenax.studio.approvals.ReviewService;
import com.leenax.studio.approvals.ReviewService.Target;
import com.leenax.studio.approvals.ReviewStore;
import com.leenax.studio.approvals.ReviewStore.ResourceRow;
import com.leenax.studio.approvals.ReviewStore.VersionRow;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.common.SqlStates;
import com.leenax.studio.documents.DocumentStore;
import com.leenax.studio.documents.DocumentText;
import com.leenax.studio.notes.Learning.Bank;
import com.leenax.studio.notes.Learning.Notes;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.notes.MaterialsAi.Passage;
import com.leenax.studio.notes.MaterialsAi.TopicBrief;
import com.leenax.studio.projects.ProjectService;
import com.leenax.studio.syllabus.AnalysisStore;
import com.leenax.studio.syllabus.AnalysisStore.JobRow;
import com.leenax.studio.syllabus.AnalysisWorker;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Set;
import com.leenax.studio.topicanalysis.AnalysisPlans;
import com.leenax.studio.topicanalysis.TopicAnalysis;
import com.leenax.studio.topicanalysis.TopicAnalysisService;

/**
 * Notes and question banks (E4): what each approved topic has, writing them with your AI
 * account, and keeping them tied to the approved versions they were written from.
 */
@Service
public class MaterialsService implements AnalysisWorker.Runner {

    private static final Logger log = LoggerFactory.getLogger(MaterialsService.class);
    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_PASSAGE_PAGES = 14;
    static final int MAX_PASSAGE_CHARS = 40_000;
    static final int MAX_NOTES_CHARS = 40_000;
    static final String EDIT = "Only the project's owners and editors can do this";

    // ---- requests and answers ----------------------------------------------------------------------

    public record NotesRequest(@NotBlank String topicId, @NotBlank String level, @NotNull UUID accountId, @NotBlank String model,
                               @Size(max = 1000) String guidance) {
    }

    public record QuestionsRequest(@NotBlank String topicId, @NotBlank String notesLevel, @Min(1) @Max(30) int count,
                                   List<String> difficulties, List<String> styles, @NotNull UUID accountId, @NotBlank String model,
                                   @Size(max = 1000) String guidance, boolean allowAllNone) {
    }

    public record ByHandRequest(@NotBlank String topicId, String level) {
    }

    public record RegenerateRequest(@NotNull UUID versionId, @NotBlank String questionId, @NotNull UUID accountId,
                                    @NotBlank String model, @Size(max = 1000) String guidance) {
    }

    public record TopicRow(String id, String title, int depth, String path, String difficulty) {
    }

    public record Material(UUID resourceId, String kind, String topicId, String level, String title, Integer latestNo,
                           String latestStatus, Integer approvedNo, UUID approvedVersionId, Integer questionCount) {
    }

    public record JobInfo(UUID id, String kind, String target, String status, String error, String summary, Instant createdAt,
                          String startedBy, UUID resultVersionId) {
    }

    public record Overview(boolean topicsApproved, Integer topicsVersionNo, List<TopicRow> topics, List<Material> notes,
                           List<Material> banks, List<JobInfo> jobs, boolean canEdit, boolean canReview) {
    }

    private final ReviewService reviews;
    private final ReviewStore reviewStore;
    private final AnalysisStore jobs;
    private final DocumentStore documents;
    private final ProjectService projects;
    private final TextAi textAi;
    private final AuditService audit;
    private final NotesRules notesRules;
    private final McqRules mcqRules;

    public MaterialsService(ReviewService reviews, ReviewStore reviewStore, AnalysisStore jobs, DocumentStore documents,
                            ProjectService projects, TextAi textAi, AuditService audit, NotesRules notesRules, McqRules mcqRules) {
        this.reviews = reviews;
        this.reviewStore = reviewStore;
        this.jobs = jobs;
        this.documents = documents;
        this.projects = projects;
        this.textAi = textAi;
        this.audit = audit;
        this.notesRules = notesRules;
        this.mcqRules = mcqRules;
    }

    // ---- overview ----------------------------------------------------------------------------------

    public Overview overview(UUID projectId) {
        projects.requireAccess(projectId, false, null);
        Optional<VersionRow> topics = reviews.approved(projectId, Kind.TOPICS);
        List<TopicRow> rows = topics.map(t -> topicRows(read(t.content()))).orElse(List.of());
        List<Material> notes = materials(projectId, Kind.NOTES), banks = materials(projectId, Kind.MCQ);
        List<JobInfo> running = new ArrayList<>();
        for (String kind : List.of("NOTES", "MCQ")) {
            for (JobRow j : jobs.recent(projectId, kind)) {
                running.add(new JobInfo(j.id(), j.kind(), j.target(), j.status(), j.error(), j.summary(), j.createdAt(), j.createdBy(), j.resultVersionId()));
            }
        }
        return new Overview(topics.isPresent(), topics.map(VersionRow::versionNo).orElse(null), rows, notes, banks, running,
                projects.canEdit(projectId), projects.canReview(projectId));
    }

    private List<Material> materials(UUID projectId, Kind kind) {
        List<Material> out = new ArrayList<>();
        for (ResourceRow r : reviewStore.resources(projectId, kind)) {
            List<VersionRow> vs = reviewStore.versions(r.id());
            VersionRow newest = vs.isEmpty() ? null : vs.get(0);
            VersionRow approved = vs.stream().filter(v -> v.status() == Status.APPROVED).findFirst().orElse(null);
            Integer count = null;
            if (kind == Kind.MCQ && newest != null) {
                count = reviewStore.version(newest.id()).map(v -> McqRules.read(read(v.content())).questions().size()).orElse(null);
            }
            out.add(new Material(r.id(), kind.name(), r.topicId(), r.level(), r.title(), newest == null ? null : newest.versionNo(),
                    newest == null ? null : newest.status().name(), approved == null ? null : approved.versionNo(),
                    approved == null ? null : approved.id(), count));
        }
        return out;
    }

    /** The included topics of an approved list, in order, with their place in the outline. */
    public static List<TopicRow> topicRows(JsonNode topics) {
        List<TopicRow> out = new ArrayList<>();
        walk(topics.path("nodes"), 1, "", out);
        return out;
    }

    private static void walk(JsonNode nodes, int depth, String path, List<TopicRow> out) {
        for (JsonNode n : nodes) {
            if (!n.path("selected").asBoolean(true)) continue;
            String title = n.path("title").asText();
            out.add(new TopicRow(n.path("id").asText(), title, depth, path.isEmpty() ? null : path,
                    n.path("difficulty").isTextual() ? n.path("difficulty").asText() : null));
            walk(n.path("children"), depth + 1, path.isEmpty() ? title : path + " › " + title, out);
        }
    }

    // ---- starting work -----------------------------------------------------------------------------

    public JobInfo generateNotes(UUID projectId, NotesRequest req) {
        projects.requireAccess(projectId, true, EDIT);
        if (!Learning.LEVELS.contains(req.level())) throw ApiException.badRequest("Choose a level");
        topic(projectId, req.topicId());
        notReviewing(projectId, Kind.NOTES, req.topicId(), req.level());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("topicId", req.topicId());
        input.put("level", req.level());
        input.put("accountId", req.accountId());
        input.put("model", req.model().strip());
        if (req.guidance() != null && !req.guidance().isBlank()) input.put("guidance", req.guidance().strip());
        return queue(projectId, "NOTES", req.topicId() + ":" + req.level(), input, "Notes for this topic and level are already being written.");
    }

    public JobInfo generateQuestions(UUID projectId, QuestionsRequest req) {
        projects.requireAccess(projectId, true, EDIT);
        topic(projectId, req.topicId());
        approvedNotes(projectId, req.topicId(), req.notesLevel());
        notReviewing(projectId, Kind.MCQ, req.topicId(), null);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("topicId", req.topicId());
        input.put("notesLevel", req.notesLevel());
        input.put("count", req.count());
        input.put("difficulties", filter(req.difficulties(), Learning.DIFFICULTIES));
        input.put("styles", filter(req.styles(), Learning.STYLES));
        input.put("accountId", req.accountId());
        input.put("model", req.model().strip());
        input.put("allowAllNone", req.allowAllNone());
        if (req.guidance() != null && !req.guidance().isBlank()) input.put("guidance", req.guidance().strip());
        return queue(projectId, "MCQ", req.topicId(), input, "Questions for this topic are already being written.");
    }

    private JobInfo queue(UUID projectId, String kind, String target, Map<String, Object> input, String busy) {
        UUID id = UUID.randomUUID();
        try {
            jobs.insert(id, projectId, kind, target, write(input));
        } catch (RuntimeException e) {
            if (SqlStates.find(e).map(x -> "23505".equals(x.getSQLState())).orElse(false)) throw new ApiException(ErrorCode.CONFLICT, busy);
            throw e;
        }
        audit.record(kind.equals("NOTES") ? "NOTES_GENERATION_STARTED" : "MCQ_GENERATION_STARTED", "project", projectId, Map.of("target", target));
        JobRow j = jobs.job(id).orElseThrow();
        return new JobInfo(j.id(), j.kind(), j.target(), j.status(), j.error(), j.summary(), j.createdAt(), j.createdBy(), j.resultVersionId());
    }

    /** A blank draft of notes, or an empty bank, to write by hand. */
    @Transactional
    public ResourceState byHand(UUID projectId, Kind kind, ByHandRequest req) {
        projects.requireAccess(projectId, true, EDIT);
        TopicRow t = topic(projectId, req.topicId());
        if (kind == Kind.NOTES) {
            if (req.level() == null || !Learning.LEVELS.contains(req.level())) throw ApiException.badRequest("Choose a level");
            if (reviewStore.resource(projectId, Kind.NOTES, t.id(), req.level()).isPresent()) {
                throw new ApiException(ErrorCode.CONFLICT, "These notes already exist. Edit their newest version instead.");
            }
            VersionRow topics = reviews.approved(projectId, Kind.TOPICS).orElseThrow();
            ObjectNode doc = JSON.createObjectNode().put("type", "doc");
            doc.putArray("content").add(NoteDoc.fromSections(t.title(), JSON.createArrayNode()).path("content").get(0));
            Notes blank = new Notes(t.id(), t.title(), req.level(), null, doc, List.of(), null);
            VersionRow v = reviews.createVersion(notesTarget(projectId, t, req.level()), JSON.valueToTree(blank), Origin.MANUAL, null,
                    topics.id(), "Written by hand");
            return reviews.stateOf(v.resourceId());
        }
        if (reviewStore.resource(projectId, Kind.MCQ, t.id(), null).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "This topic already has a question bank.");
        }
        VersionRow notes = anyApprovedNotes(projectId, t.id());
        Bank empty = new Bank(t.id(), t.title(), null, levelOf(notes), false, List.of());
        VersionRow v = reviews.createVersion(bankTarget(projectId, t, notes), JSON.valueToTree(empty), Origin.MANUAL, null, notes.id(),
                "Started by hand");
        return reviews.stateOf(v.resourceId());
    }

    /**
     * Ties notes to the approved topic list, or a bank to the approved notes, again after
     * those changed. The content stays; check it still fits, then send it for review.
     */
    @Transactional
    public ResourceState update(UUID resourceId) {
        ResourceRow r = reviewStore.resource(resourceId).orElseThrow(() -> ApiException.notFound("Resource"));
        projects.requireAccess(r.projectId(), true, EDIT);
        UUID upstream = switch (r.kind()) {
            case NOTES -> reviews.approved(r.projectId(), Kind.TOPICS)
                    .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Approve the topic list first.")).id();
            case MCQ -> {
                VersionRow newest = reviewStore.versions(r.id()).get(0);
                Bank b = McqRules.read(read(reviewStore.version(newest.id()).orElseThrow().content()));
                yield (b.notesLevel() != null ? approvedNotesOpt(r.projectId(), r.topicId(), b.notesLevel())
                        : Optional.<VersionRow>empty()).orElseGet(() -> anyApprovedNotes(r.projectId(), r.topicId())).id();
            }
            default -> throw ApiException.badRequest("Use the syllabus and topics pages for these");
        };
        VersionRow newest = reviewStore.versions(r.id()).get(0);
        if (newest.status() == Status.IN_REVIEW) throw new ApiException(ErrorCode.CONFLICT, "Version " + newest.versionNo() + " is in review. Withdraw it first.");
        JsonNode content = read(reviewStore.version(newest.id()).orElseThrow().content());
        if (newest.status() == Status.DRAFT) {
            JsonNode clean = (r.kind() == Kind.NOTES ? notesRules : mcqRules).normalise(content, upstream);
            String canonical = com.leenax.studio.approvals.Review.canonical(clean);
            if (reviewStore.saveDraft(newest.id(), canonical, com.leenax.studio.approvals.Review.hash(canonical), upstream) == 0) {
                throw new ApiException(ErrorCode.CONFLICT, "This version changed meanwhile. Reload and try again.");
            }
        } else {
            reviews.createVersion(new Target(r.projectId(), r.kind(), r.topicId(), r.level(), null, List.of()), content, Origin.EDIT,
                    newest.id(), upstream, "Tied to the newly approved " + (r.kind() == Kind.NOTES ? "topic list" : "notes"));
        }
        return reviews.stateOf(r.id());
    }

    // ---- the jobs ----------------------------------------------------------------------------------

    @Override
    public boolean handles(String kind) {
        return kind.equals("NOTES") || kind.equals("MCQ");
    }

    @Override
    public void process(UUID jobId) {
        JobRow j = jobs.job(jobId).orElse(null);
        if (j == null || !(j.status().equals("QUEUED") || j.status().equals("RUNNING"))) return;
        jobs.running(jobId);
        try {
            JsonNode in = JSON.readTree(j.input());
            VersionRow made = j.kind().equals("NOTES") ? writeNotes(j, in) : writeQuestions(j, in);
            jobs.succeeded(jobId, made.id());
        } catch (ProviderException e) {
            if (e.kind().retryable() && j.attempts() + 1 < 2) jobs.retryLater(jobId, e.getMessage() + " — trying again shortly", 120);
            else jobs.failed(jobId, e.getMessage());
        } catch (ApiException e) {
            jobs.failed(jobId, e.getMessage());
        } catch (Exception e) {
            log.error("{} job {} failed", j.kind(), jobId, e);
            jobs.failed(jobId, "Writing failed unexpectedly");
        }
    }

    private VersionRow writeNotes(JobRow j, JsonNode in) {
        String topicId = in.path("topicId").asText(), level = in.path("level").asText(), model = in.path("model").asText();
        VersionRow topicsVersion = reviews.approved(j.projectId(), Kind.TOPICS)
                .orElseThrow(() -> ApiException.badRequest("The topic list isn't approved any more"));
        TopicBrief brief = brief(j.projectId(), read(topicsVersion.content()), topicId);
        List<Passage> passages = passages(j.projectId(), read(topicsVersion.content()), topicId, brief.title());
        JsonNode reply = textAi.askJson(UUID.fromString(in.path("accountId").asText()), model, MaterialsAi.notesSystem(),
                MaterialsAi.notesPrompt(brief, level, passages, in.path("guidance").asText(null)));
        Notes notes = MaterialsAi.readNotes(reply, brief, level, passages, model);
        if (NoteDoc.plainText(notes.doc()).length() < NotesRules.MIN_CHARS) throw ApiException.badRequest("The AI wrote almost nothing. Try again or use another model.");
        JsonNode content = JSON.valueToTree(notes);
        TopicRow t = new TopicRow(topicId, brief.title(), 0, brief.path(), brief.difficulty());
        Optional<ResourceRow> existing = reviewStore.resource(j.projectId(), Kind.NOTES, topicId, level);
        jobs.summary(j.id(), notes.references().size() + " references, " + notes.references().stream().filter(r -> Boolean.TRUE.equals(r.found())).count()
                + " found word for word on their page");
        if (existing.isPresent()) {
            VersionRow newest = reviewStore.versions(existing.get().id()).get(0);
            if (newest.status() == Status.DRAFT && topicsVersion.id().equals(newest.dependsOn())) {
                return reviews.replaceDraft(newest, content, "Rewritten by the AI with " + model);
            }
            return reviews.createVersion(notesTarget(j.projectId(), t, level), content, Origin.AI, newest.id(), topicsVersion.id(),
                    "Written by the AI with " + model);
        }
        return reviews.createVersion(notesTarget(j.projectId(), t, level), content, Origin.AI, null, topicsVersion.id(),
                "Written by the AI with " + model);
    }

    private VersionRow writeQuestions(JobRow j, JsonNode in) {
        String topicId = in.path("topicId").asText(), model = in.path("model").asText(), level = in.path("notesLevel").asText();
        VersionRow notes = approvedNotes(j.projectId(), topicId, level);
        Notes n = NotesRules.read(read(notes.content()));
        TopicRow t = new TopicRow(topicId, n.topicTitle(), 0, null, null);
        Optional<ResourceRow> existing = reviewStore.resource(j.projectId(), Kind.MCQ, topicId, null);
        VersionRow newest = existing.map(r -> reviewStore.versions(r.id()).get(0)).orElse(null);
        Bank current = newest == null ? null : McqRules.read(read(reviewStore.version(newest.id()).orElseThrow().content()));
        List<Question> have = current == null ? List.of() : McqRules.list(current.questions());
        boolean allowAllNone = in.path("allowAllNone").asBoolean(false) || (current != null && Boolean.TRUE.equals(current.allowAllNone()));
        String text = NoteDoc.plainText(n.doc());
        if (text.length() > MAX_NOTES_CHARS) text = text.substring(0, MAX_NOTES_CHARS);
        int count = in.path("count").asInt(10);
        Optional<TopicAnalysis> plan = TopicAnalysisService.approved(reviewStore, j.projectId(), topicId)
                .filter(a -> a.mcqPlan() != null && !a.mcqPlan().isEmpty());
        String prompt = MaterialsAi.questionPrompt(n.topicTitle(), text, count, strings(in.path("difficulties")),
                strings(in.path("styles")), have.stream().map(Question::stem).toList(), in.path("guidance").asText(null), null)
                + plan.map(a -> AnalysisPlans.questionPlan(a, count)).orElse("");
        String system = MaterialsAi.questionSystem(allowAllNone) + (plan.isPresent() ? MaterialsAi.PLANNED : "");
        JsonNode reply = textAi.askJson(UUID.fromString(in.path("accountId").asText()), model, system, prompt);
        String label = Learning.levelLabel(level) + " notes, version " + notes.versionNo();
        Set<String> objectiveIds = plan.map(a -> a.objectives().stream().map(TopicAnalysis.Objective::id).collect(java.util.stream.Collectors.toSet()))
                .orElse(Set.of());
        MaterialsAi.Drafted drafted = MaterialsAi.readQuestions(reply, have, allowAllNone, label, objectiveIds);
        if (drafted.accepted().isEmpty()) {
            throw ApiException.badRequest("None of the AI's questions passed the checks (" + String.join("; ", drafted.dropped().stream().limit(4).toList()) + ")");
        }
        String summary = "Kept " + drafted.accepted().size() + " of " + (drafted.accepted().size() + drafted.dropped().size()) + " questions"
                + (drafted.dropped().isEmpty() ? "" : ". Left out: " + String.join("; ", drafted.dropped()))
                + plan.map(a -> ". Plan from the topic analysis: " + AnalysisPlans.coverage(a, count,
                        drafted.accepted().stream().map(Question::subtopic).toList())).orElse("");
        jobs.summary(j.id(), summary);
        List<Question> all = new ArrayList<>(have);
        all.addAll(drafted.accepted());
        Bank bank = new Bank(topicId, n.topicTitle(), null, level, allowAllNone, all);
        JsonNode content = JSON.valueToTree(bank);
        if (newest != null && newest.status() == Status.DRAFT && notes.id().equals(newest.dependsOn())) {
            return reviews.replaceDraft(newest, content, "Added " + drafted.accepted().size() + " questions written by the AI with " + model);
        }
        return reviews.createVersion(bankTarget(j.projectId(), t, notes), content, Origin.AI, newest == null ? null : newest.id(), notes.id(),
                "Added " + drafted.accepted().size() + " questions written by the AI with " + model);
    }

    /** One replacement question, not saved: the editor puts it in place of the old one, to save or discard. */
    public Question regenerate(UUID resourceId, RegenerateRequest req) {
        ResourceRow r = reviewStore.resource(resourceId).filter(x -> x.kind() == Kind.MCQ).orElseThrow(() -> ApiException.notFound("Question bank"));
        projects.requireAccess(r.projectId(), true, EDIT);
        VersionRow v = reviewStore.version(req.versionId()).filter(x -> x.resourceId().equals(r.id()))
                .orElseThrow(() -> ApiException.notFound("Version"));
        Bank bank = McqRules.read(read(v.content()));
        Question old = McqRules.list(bank.questions()).stream().filter(q -> q.id().equals(req.questionId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Question"));
        VersionRow notes = v.dependsOn() == null ? anyApprovedNotes(r.projectId(), r.topicId())
                : reviewStore.version(v.dependsOn()).orElseThrow(() -> ApiException.notFound("Notes"));
        Notes n = NotesRules.read(read(notes.content()));
        String text = NoteDoc.plainText(n.doc());
        if (text.length() > MAX_NOTES_CHARS) text = text.substring(0, MAX_NOTES_CHARS);
        List<Question> others = McqRules.list(bank.questions()).stream().filter(q -> !q.id().equals(old.id())).toList();
        boolean allowAllNone = Boolean.TRUE.equals(bank.allowAllNone());
        JsonNode reply = textAi.askJson(req.accountId(), req.model().strip(), MaterialsAi.questionSystem(allowAllNone),
                MaterialsAi.questionPrompt(n.topicTitle(), text, 1, old.difficulty() == null ? List.of() : List.of(old.difficulty()),
                        List.of(old.style()), others.stream().map(Question::stem).toList(), req.guidance(), old));
        MaterialsAi.Drafted d = MaterialsAi.readQuestions(reply, others, allowAllNone,
                Learning.levelLabel(n.level()) + " notes, version " + notes.versionNo());
        if (d.accepted().isEmpty()) throw ApiException.badRequest("The new question didn't pass the checks (" + String.join("; ", d.dropped()) + "). Try again.");
        Question q = d.accepted().get(0);
        return new Question(old.id(), q.style(), q.difficulty(), q.stem(), q.code(), q.options(), q.answer(), q.explanation(),
                q.optionExplanations(), q.subtopic(), q.source(), "UNVERIFIED");
    }

    // ---- what the AI reads -------------------------------------------------------------------------

    public TopicBrief brief(UUID projectId, JsonNode topics, String topicId) {
        Map<String, JsonNode> byId = new HashMap<>();
        Map<String, String> pathOf = new HashMap<>();
        index(topics.path("nodes"), "", byId, pathOf);
        JsonNode node = byId.get(topicId);
        if (node == null || !node.path("selected").asBoolean(true)) throw ApiException.badRequest("That topic isn't in the approved topic list");
        List<String> subtopics = new ArrayList<>();
        node.path("children").forEach(c -> {
            if (c.path("selected").asBoolean(true)) subtopics.add(c.path("title").asText());
        });
        List<String> prereq = new ArrayList<>();
        node.path("prerequisites").forEach(p -> Optional.ofNullable(byId.get(p.asText())).ifPresent(x -> prereq.add(x.path("title").asText())));
        List<String> outcomes = new ArrayList<>();
        reviews.approved(projectId, Kind.SYLLABUS).ifPresent(s -> read(s.content()).path("outcomes").forEach(o ->
                outcomes.add((o.path("code").isTextual() ? o.path("code").asText() + " " : "") + o.path("text").asText())));
        String path = pathOf.get(topicId);
        return new TopicBrief(topicId, node.path("title").asText(), path == null || path.isEmpty() ? null : path, subtopics, prereq,
                outcomes, node.path("difficulty").isTextual() ? node.path("difficulty").asText() : null);
    }

    private static void index(JsonNode nodes, String path, Map<String, JsonNode> byId, Map<String, String> pathOf) {
        for (JsonNode n : nodes) {
            byId.put(n.path("id").asText(), n);
            pathOf.put(n.path("id").asText(), path);
            index(n.path("children"), path.isEmpty() ? n.path("title").asText() : path + " › " + n.path("title").asText(), byId, pathOf);
        }
    }

    /**
     * The document pages for a topic: the pages its syllabus items cite first, then the best
     * search matches for its name, up to 14 pages and 40,000 characters.
     */
    public List<Passage> passages(UUID projectId, JsonNode topics, String topicId, String title) {
        Map<String, JsonNode> byId = new HashMap<>();
        index(topics.path("nodes"), "", byId, new HashMap<>());
        JsonNode node = byId.get(topicId);
        LinkedHashMap<String, Passage> chosen = new LinkedHashMap<>();
        Map<UUID, String> labels = new LinkedHashMap<>();
        Map<UUID, Map<Integer, String>> pageCache = new HashMap<>();
        int[] chars = {0};
        if (node != null) {
            List<JsonNode> withKids = new ArrayList<>();
            withKids.add(node);
            node.path("children").forEach(withKids::add);
            for (JsonNode n : withKids) {
                for (JsonNode s : n.path("sources")) {
                    UUID doc = uuid(s.path("documentId").asText()), ver = uuid(s.path("versionId").asText());
                    int page = s.path("page").asInt(-1);
                    if (doc == null || ver == null || page < 1) continue;
                    String text = pageCache.computeIfAbsent(ver, jobs::pages).get(page);
                    String docTitle = documents.document(doc).map(DocumentStore.DocRow::title).orElse("Document");
                    add(chosen, labels, chars, doc, ver, docTitle, page, text);
                }
            }
        }
        List<String> words = DocumentText.queryWords(title);
        if (!words.isEmpty()) {
            for (DocumentStore.Candidate c : rankCandidates(documents.candidates(projectId, words, 200), words)) {
                String text = pageCache.computeIfAbsent(c.versionId(), jobs::pages).get(c.pageNo());
                add(chosen, labels, chars, c.documentId(), c.versionId(), c.title(), c.pageNo(), text);
                if (chosen.size() >= MAX_PASSAGE_PAGES) break;
            }
        }
        return new ArrayList<>(chosen.values());
    }

    static List<DocumentStore.Candidate> rankCandidates(List<DocumentStore.Candidate> found, List<String> words) {
        return found.stream().sorted((a, b) -> Integer.compare(DocumentText.score(b.text(), words), DocumentText.score(a.text(), words))).toList();
    }

    private static void add(Map<String, Passage> chosen, Map<UUID, String> labels, int[] chars, UUID doc, UUID ver, String title, int page,
                            String text) {
        if (text == null || text.isBlank() || chosen.size() >= MAX_PASSAGE_PAGES) return;
        String key = ver + ":" + page;
        if (chosen.containsKey(key)) return;
        String t = text.length() > 6000 ? text.substring(0, 6000) : text;
        if (chars[0] + t.length() > MAX_PASSAGE_CHARS) return;
        chars[0] += t.length();
        String label = labels.computeIfAbsent(ver, v -> "D" + (labels.size() + 1));
        chosen.put(key, new Passage(label, doc, ver, title, page, t));
    }

    // ---- lookups -----------------------------------------------------------------------------------

    TopicRow topic(UUID projectId, String topicId) {
        VersionRow topics = reviews.approved(projectId, Kind.TOPICS)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Approve the topic list first. Notes and questions are written only for approved topics."));
        return topicRows(read(topics.content())).stream().filter(t -> t.id().equals(topicId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("That topic isn't included in the approved topic list"));
    }

    private void notReviewing(UUID projectId, Kind kind, String topicId, String level) {
        reviewStore.resource(projectId, kind, topicId, level).ifPresent(r -> {
            VersionRow newest = reviewStore.versions(r.id()).get(0);
            if (newest.status() == Status.IN_REVIEW) {
                throw new ApiException(ErrorCode.CONFLICT, "Version " + newest.versionNo() + " is in review. Withdraw it first, or wait for the decision.");
            }
        });
    }

    VersionRow approvedNotes(UUID projectId, String topicId, String level) {
        return approvedNotesOpt(projectId, topicId, level).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT,
                "Approve the " + Learning.levelLabel(level).toLowerCase() + " notes for this topic first. Questions are written from approved notes."));
    }

    Optional<VersionRow> approvedNotesOpt(UUID projectId, String topicId, String level) {
        return reviewStore.resource(projectId, Kind.NOTES, topicId, level).flatMap(r -> reviewStore.approved(r.id()))
                .flatMap(v -> reviewStore.version(v.id()));
    }

    private VersionRow anyApprovedNotes(UUID projectId, String topicId) {
        for (String level : Learning.LEVELS) {
            Optional<VersionRow> v = approvedNotesOpt(projectId, topicId, level);
            if (v.isPresent()) return v.get();
        }
        throw new ApiException(ErrorCode.CONFLICT, "Approve notes for this topic first. Questions are written from approved notes.");
    }

    private String levelOf(VersionRow notes) {
        return NotesRules.read(read(notes.content())).level();
    }

    private Target notesTarget(UUID projectId, TopicRow t, String level) {
        List<UUID> links = reviewStore.resource(projectId, Kind.TOPICS).map(r -> List.of(r.id())).orElse(List.of());
        return new Target(projectId, Kind.NOTES, t.id(), level, t.title() + " (" + Learning.levelLabel(level) + ")", links);
    }

    private Target bankTarget(UUID projectId, TopicRow t, VersionRow notes) {
        return new Target(projectId, Kind.MCQ, t.id(), null, t.title(), List.of(notes.resourceId()));
    }

    static List<String> filter(List<String> in, List<String> allowed) {
        return in == null ? List.of() : in.stream().filter(allowed::contains).distinct().toList();
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonNode read(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("Stored content isn't JSON", e);
        }
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
