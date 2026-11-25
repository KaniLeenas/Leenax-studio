package com.leenax.studio.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.text.TextAi;
import com.leenax.studio.approvals.Review;
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
import com.leenax.studio.notes.Learning;
import com.leenax.studio.notes.Learning.Bank;
import com.leenax.studio.notes.Learning.Notes;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.notes.MaterialsAi.Passage;
import com.leenax.studio.notes.MaterialsAi.TopicBrief;
import com.leenax.studio.notes.MaterialsService;
import com.leenax.studio.notes.McqRules;
import com.leenax.studio.notes.NoteDoc;
import com.leenax.studio.notes.NotesRules;
import com.leenax.studio.projects.ProjectService;
import com.leenax.studio.qa.Qa.Claim;
import com.leenax.studio.qa.Qa.Confirmation;
import com.leenax.studio.qa.Qa.CorrectionRow;
import com.leenax.studio.qa.Qa.Finding;
import com.leenax.studio.qa.Qa.IssueRow;
import com.leenax.studio.qa.Qa.QuestionCheck;
import com.leenax.studio.qa.Qa.RunResult;
import com.leenax.studio.qa.Qa.RunRow;
import com.leenax.studio.qa.Qa.Tally;
import com.leenax.studio.qa.QaAi.Answer;
import com.leenax.studio.qa.QaAi.Verdict;
import com.leenax.studio.qa.QaRules.Block;
import com.leenax.studio.syllabus.AnalysisStore;
import com.leenax.studio.syllabus.AnalysisStore.JobRow;
import com.leenax.studio.syllabus.AnalysisWorker;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Academic QA (E5): checking notes and question banks with an AI model (and a second one
 * where set up), recording issues, corrections that a person accepts or rejects, rechecks,
 * and each topic's QA report.
 */
@Service
public class QaService implements AnalysisWorker.Runner {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);
    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_NOTES_CHARS = 30_000;
    static final int MAX_QUESTIONS = 80;
    static final String WHO = "Only the project's owners, editors and reviewers can run QA";

    public record RunRequest(UUID versionId, @NotNull UUID accountId, @NotBlank String model, UUID secondAccountId, String secondModel,
                             boolean recheckOnly) {
    }

    public record Decision(@Size(max = 2000) String note) {
    }

    public record Reason(@NotBlank @Size(max = 2000) String reason) {
    }

    public record JobInfo(UUID id, String status, String error, String summary, Instant createdAt, String startedBy) {
    }

    public record ResourceQa(UUID resourceId, String kind, String writerModel, List<RunRow> runs, List<IssueRow> issues, JobInfo job,
                             boolean canRun, boolean canEdit, boolean canReview) {
    }

    public record TopicQa(String topicId, String title, int depth, List<ItemQa> items, UUID reportId, Integer reportVersionNo,
                          String reportStatus, String reportHeadline) {
    }

    public record ItemQa(UUID resourceId, String kind, String level, Integer versionNo, String versionStatus, Integer checkedVersionNo,
                         boolean current, Map<String, Integer> open, boolean running) {
    }

    public record Overview(boolean topicsApproved, List<TopicQa> topics, boolean canEdit, boolean canReview) {
    }

    private final ReviewService reviews;
    private final ReviewStore reviewStore;
    private final QaStore store;
    private final AnalysisStore jobs;
    private final MaterialsService materials;
    private final ProjectService projects;
    private final TextAi textAi;
    private final AccountService accounts;
    private final AuditService audit;

    public QaService(ReviewService reviews, ReviewStore reviewStore, QaStore store, AnalysisStore jobs, MaterialsService materials,
                     ProjectService projects, TextAi textAi, AccountService accounts, AuditService audit) {
        this.reviews = reviews;
        this.reviewStore = reviewStore;
        this.store = store;
        this.jobs = jobs;
        this.materials = materials;
        this.projects = projects;
        this.textAi = textAi;
        this.accounts = accounts;
        this.audit = audit;
    }

    // ---- reading -----------------------------------------------------------------------------------

    public ResourceQa forResource(UUID resourceId) {
        ResourceRow r = checkable(resourceId);
        projects.requireAccess(r.projectId(), false, null);
        JobInfo job = jobs.recent(r.projectId(), "QA").stream().filter(j -> j.target().equals(r.id().toString())).findFirst()
                .map(QaService::info).orElse(null);
        boolean edit = projects.canEdit(r.projectId()), review = projects.canReview(r.projectId());
        return new ResourceQa(r.id(), r.kind().name(), writerModel(r, newest(r)), store.runs(r.id()), store.issues(r.id()), job, edit || review,
                edit, review);
    }

    public Overview overview(UUID projectId) {
        projects.requireAccess(projectId, false, null);
        Optional<VersionRow> topics = reviews.approved(projectId, Kind.TOPICS);
        Map<String, JobRow> running = new HashMap<>();
        for (JobRow j : jobs.recent(projectId, "QA")) if (j.status().equals("QUEUED") || j.status().equals("RUNNING")) running.put(j.target(), j);
        List<ResourceRow> all = new ArrayList<>(reviewStore.resources(projectId, Kind.NOTES));
        all.addAll(reviewStore.resources(projectId, Kind.MCQ));
        Map<String, ResourceRow> reports = new HashMap<>();
        reviewStore.resources(projectId, Kind.QA).forEach(r -> reports.put(r.topicId(), r));
        List<TopicQa> out = new ArrayList<>();
        for (MaterialsService.TopicRow t : topics.map(v -> MaterialsService.topicRows(MaterialsService.read(v.content()))).orElse(List.of())) {
            List<ItemQa> items = new ArrayList<>();
            for (ResourceRow r : all) {
                if (!t.id().equals(r.topicId())) continue;
                VersionRow v = newest(r);
                Optional<RunRow> run = store.latestCheck(r.id());
                Map<String, Integer> open = new LinkedHashMap<>();
                store.openBySeverity(r.id(), false).forEach((k, x) -> open.put(k, (Integer) x[0]));
                items.add(new ItemQa(r.id(), r.kind().name(), r.level(), v == null ? null : v.versionNo(), v == null ? null : v.status().name(),
                        run.map(RunRow::versionNo).orElse(null), v != null && store.checkOfContent(r.id(), v.contentHash()).isPresent(), open,
                        running.containsKey(r.id().toString())));
            }
            items.sort((a, b) -> a.kind().equals(b.kind()) ? Integer.compare(levelRank(a.level()), levelRank(b.level())) : a.kind().compareTo(b.kind()) * -1);
            ResourceRow rep = reports.get(t.id());
            VersionRow rv = rep == null ? null : newest(rep);
            String headline = rv == null ? null : MaterialsService.read(reviewStore.version(rv.id()).orElseThrow().content()).path("headline").asText(null);
            out.add(new TopicQa(t.id(), t.title(), t.depth(), items, rep == null ? null : rep.id(), rv == null ? null : rv.versionNo(),
                    rv == null ? null : rv.status().name(), headline));
        }
        return new Overview(topics.isPresent(), out, projects.canEdit(projectId), projects.canReview(projectId));
    }

    // ---- starting a run ----------------------------------------------------------------------------

    public JobInfo start(UUID resourceId, RunRequest req) {
        ResourceRow r = checkable(resourceId);
        projects.requireAccess(r.projectId(), false, null);
        if (!projects.canEdit(r.projectId()) && !projects.canReview(r.projectId())) throw new ApiException(ErrorCode.FORBIDDEN, WHO);
        VersionRow v = req.versionId() == null ? newest(r) : reviewStore.version(req.versionId()).filter(x -> x.resourceId().equals(r.id()))
                .orElseThrow(() -> ApiException.notFound("Version"));
        if (v == null) throw ApiException.badRequest("There is nothing to check yet");
        if (req.recheckOnly() && store.issues(r.id()).stream().noneMatch(i -> Qa.open(i.status()))) {
            throw ApiException.badRequest("No open issues to recheck");
        }
        if (req.secondAccountId() != null && (req.secondModel() == null || req.secondModel().isBlank())) {
            throw ApiException.badRequest("Choose the second model too, or leave the second account empty");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("resourceId", r.id());
        input.put("versionId", v.id());
        input.put("accountId", req.accountId());
        input.put("model", req.model().strip());
        if (req.secondAccountId() != null) {
            input.put("secondAccountId", req.secondAccountId());
            input.put("secondModel", req.secondModel().strip());
        }
        input.put("mode", req.recheckOnly() ? "RECHECK" : "CHECK");
        UUID id = UUID.randomUUID();
        try {
            jobs.insert(id, r.projectId(), "QA", r.id().toString(), write(input));
        } catch (RuntimeException e) {
            if (SqlStates.find(e).map(x -> "23505".equals(x.getSQLState())).orElse(false)) {
                throw new ApiException(ErrorCode.CONFLICT, "QA is already running on this.");
            }
            throw e;
        }
        audit.record("QA_STARTED", "resource", r.id(), Map.of("version", v.versionNo(), "mode", input.get("mode")));
        return info(jobs.job(id).orElseThrow());
    }

    // ---- the job -----------------------------------------------------------------------------------

    @Override
    public boolean handles(String kind) {
        return kind.equals("QA");
    }

    @Override
    public void process(UUID jobId) {
        JobRow j = jobs.job(jobId).orElse(null);
        if (j == null || !(j.status().equals("QUEUED") || j.status().equals("RUNNING"))) return;
        jobs.running(jobId);
        try {
            JsonNode in = JSON.readTree(j.input());
            Ctx c = context(j, in);
            String summary = in.path("mode").asText().equals("RECHECK") ? recheckRun(c) : checkRun(c);
            jobs.summary(jobId, summary);
            jobs.succeeded(jobId, c.version.id());
        } catch (ProviderException e) {
            if (e.kind().retryable() && j.attempts() + 1 < 2) jobs.retryLater(jobId, e.getMessage() + " — trying again shortly", 120);
            else jobs.failed(jobId, e.getMessage());
        } catch (ApiException e) {
            jobs.failed(jobId, e.getMessage());
        } catch (Exception e) {
            log.error("QA job {} failed", jobId, e);
            jobs.failed(jobId, "QA failed unexpectedly");
        }
    }

    /** Everything one run needs. */
    static final class Ctx {
        JobRow job;
        ResourceRow resource;
        VersionRow version;
        JsonNode content;
        UUID account, secondAccount;
        String model, secondModel, provider, writerModel;
        String topicTitle;
        List<String> subtopics = List.of();
        List<Passage> passages = List.of();
        String notesText;
    }

    private Ctx context(JobRow j, JsonNode in) {
        Ctx c = new Ctx();
        c.job = j;
        c.resource = reviewStore.resource(UUID.fromString(in.path("resourceId").asText())).orElseThrow(() -> ApiException.notFound("Resource"));
        c.version = reviewStore.version(UUID.fromString(in.path("versionId").asText())).orElseThrow(() -> ApiException.notFound("Version"));
        c.content = MaterialsService.read(c.version.content());
        c.account = UUID.fromString(in.path("accountId").asText());
        c.model = in.path("model").asText();
        if (in.hasNonNull("secondAccountId")) {
            c.secondAccount = UUID.fromString(in.path("secondAccountId").asText());
            c.secondModel = in.path("secondModel").asText();
        }
        try {
            c.provider = accounts.get(c.account).getProvider().displayName();
        } catch (RuntimeException e) {
            c.provider = null;
        }
        c.writerModel = writerModel(c.resource, c.version);
        Optional<VersionRow> topics = reviews.approved(c.resource.projectId(), Kind.TOPICS);
        if (topics.isPresent()) {
            JsonNode t = MaterialsService.read(topics.get().content());
            try {
                TopicBrief brief = materials.brief(c.resource.projectId(), t, c.resource.topicId());
                c.topicTitle = brief.title();
                c.subtopics = brief.subtopics();
                c.passages = materials.passages(c.resource.projectId(), t, c.resource.topicId(), brief.title());
            } catch (ApiException e) {
                c.topicTitle = c.resource.title(); // the topic left the approved list: check without its passages
            }
        }
        if (c.topicTitle == null) c.topicTitle = c.resource.title();
        if (c.resource.kind() == Kind.MCQ) {
            Bank b = McqRules.read(c.content);
            VersionRow notes = b.notesVersionId() == null ? null : reviewStore.version(b.notesVersionId()).orElse(null);
            if (notes != null) {
                String text = NoteDoc.plainText(NotesRules.read(MaterialsService.read(notes.content())).doc());
                c.notesText = text.length() > MAX_NOTES_CHARS ? text.substring(0, MAX_NOTES_CHARS) : text;
            }
        }
        return c;
    }

    /** A full check: recheck what's open, then look for new problems, then record the run. */
    String checkRun(Ctx c) {
        UUID runId = UUID.randomUUID();
        List<Finding> found = new ArrayList<>();
        Map<String, Tally> cats = new LinkedHashMap<>();
        List<Claim> claims = null;
        List<QuestionCheck> checks = null;
        List<String> limitations = new ArrayList<>();
        if (c.passages.isEmpty()) limitations.add("No course document pages were found for this topic, so nothing was checked against them.");

        if (c.resource.kind() == Kind.NOTES) {
            Notes n = NotesRules.read(c.content);
            List<Block> blocks = QaRules.blocks(n.doc());
            QaAi.NotesCheck ai = QaAi.readNotes(textAi.askJson(c.account, c.model, QaAi.notesSystem(),
                    QaAi.notesPrompt(c.topicTitle, Learning.levelLabel(n.level()), c.subtopics, c.passages, blocks)), blocks, c.passages);
            claims = ai.claims();
            List<Finding> aiFound = confirm(c, ai.findings(), f -> currentNotesText(blocks, f.itemRef(), f.original()));
            found.addAll(aiFound);
            found.addAll(QaRules.notesFindings(n, c.subtopics));
            cats.put("ACCURACY", Tally.of(claims.size(), 0));
            cats.put("COVERAGE", Tally.of(c.subtopics.size(), 0));
            cats.put("EXPLANATIONS", Tally.of(blocks.size(), 0));
            cats.put("REFERENCES", Tally.of(n.references() == null ? 0 : n.references().size(), 0));
        } else {
            Bank b = McqRules.read(c.content);
            List<Question> qs = McqRules.list(b.questions());
            if (qs.size() > MAX_QUESTIONS) limitations.add("Only the first " + MAX_QUESTIONS + " questions were checked by the AI.");
            List<Question> checkable = qs.stream().limit(MAX_QUESTIONS).toList();
            Map<Integer, Answer> first = QaAi.readAnswers(textAi.askJson(c.account, c.model, QaAi.answerSystem(),
                    QaAi.answerPrompt(c.topicTitle, c.notesText, c.passages, checkable)), c.passages);
            Map<Integer, Answer> second = c.secondAccount == null ? Map.of() : QaAi.readAnswers(textAi.askJson(c.secondAccount, c.secondModel,
                    QaAi.answerSystem(), QaAi.answerPrompt(c.topicTitle, c.notesText, c.passages, checkable)), c.passages);
            checks = new ArrayList<>();
            int keysChecked = 0;
            for (int i = 0; i < checkable.size(); i++) {
                Question q = checkable.get(i);
                Answer a = first.get(i + 1), s = second.get(i + 1);
                boolean usable = q.options() != null && q.options().size() == 4 && q.answer() != null && q.answer() >= 0 && q.answer() < 4;
                if (usable && a != null && a.answer() != null) keysChecked++;
                Finding f = usable ? QaAi.keyFinding(q, i + 1, a) : null;
                if (f != null && c.secondModel != null) {
                    boolean agrees = s != null && s.answer() != null && (f.type().equals("WRONG_KEY") ? s.answer().equals(a.answer())
                            : s.ambiguous() || !s.answer().equals(q.answer()));
                    String note = s == null || s.answer() == null ? "No answer from the second model"
                            : "Its answer: " + QaAi.LETTERS[s.answer()] + (s.ambiguous() ? ", and it finds the question ambiguous" : "");
                    f = f.withConfirmations(List.of(new Confirmation(c.secondModel, agrees, note)));
                }
                if (f != null) found.add(f);
                if (usable && a != null && a.difficulty() != null && q.difficulty() != null
                        && Math.abs(Learning.DIFFICULTIES.indexOf(a.difficulty()) - Learning.DIFFICULTIES.indexOf(q.difficulty())) == 2) {
                    found.add(new Finding(q.id(), "Question " + (i + 1), "WRONG_DIFFICULTY", "MINOR", q.stem(),
                            "Labelled " + q.difficulty().toLowerCase() + ", but the checker judges it " + a.difficulty().toLowerCase() + ".", "AI",
                            List.of(a.evidence()), "Change the difficulty to " + a.difficulty().toLowerCase() + ".", null, List.of()));
                }
                String verdict = !usable ? "NOT_CHECKED" : f == null ? "PASSED" : "FAILED";
                checks.add(new QuestionCheck(q.id(), i + 1, q.answer(), a == null ? null : a.answer(), s == null ? null : s.answer(), verdict,
                        q.difficulty(), a == null ? null : a.difficulty()));
            }
            List<Finding> review = QaAi.readReview(textAi.askJson(c.account, c.model, QaAi.reviewSystem(),
                    QaAi.reviewPrompt(c.topicTitle, c.passages, checkable)), checkable, c.passages);
            found.addAll(confirm(c, review, f -> questionText(qs, f.itemRef())));
            List<Bank> others = reviewStore.newestOfKind(c.resource.projectId(), Kind.MCQ).stream()
                    .filter(v -> !v.resourceId().equals(c.resource.id())).map(v -> McqRules.read(MaterialsService.read(v.content()))).toList();
            found.addAll(QaRules.bankFindings(b, others));
            cats.put("MCQ_KEYS", Tally.of(keysChecked, 0));
            cats.put("ACCURACY", Tally.of(checkable.size(), 0));
            cats.put("EXPLANATIONS", Tally.of(checkable.size(), 0));
            cats.put("DIFFICULTY", Tally.of(checkable.size(), 0));
            cats.put("DUPLICATES", Tally.of(qs.size(), 0));
        }
        if (c.writerModel != null && c.writerModel.equalsIgnoreCase(c.model)) {
            limitations.add("Checked by " + c.model + ", which also wrote this, so the check isn't independent.");
        }

        // what was open before: recheck it on this version first
        int[] rechecked = recheck(c, runId, false);
        // then new findings, leaving out what is still open already
        List<IssueRow> still = store.issues(c.resource.id()).stream().filter(i -> Qa.open(i.status())).toList();
        int added = 0, proposals = 0;
        RunResult result = new RunResult(cats, claims, checks, limitations, null);
        store.insertRun(runId, c.resource.projectId(), c.resource.id(), c.version.id(), c.version.contentHash(), c.job.id(), "CHECK", c.model,
                c.provider, c.secondModel,
                c.writerModel, independent(c), write(result));
        int number = store.nextNumber(c.resource.id());
        for (Finding f : dedupe(found)) {
            if (still.stream().anyMatch(i -> same(i, f))) continue;
            UUID issueId = UUID.randomUUID();
            store.insertIssue(issueId, c.resource.projectId(), c.resource.id(), runId, c.version.id(), number++, f);
            added++;
            if (f.proposal() != null) {
                store.insertCorrection(UUID.randomUUID(), c.resource.projectId(), issueId, f.proposal());
                proposals++;
            }
        }
        long critical = store.issues(c.resource.id()).stream().filter(i -> Qa.open(i.status()) && i.severity().equals("CRITICAL")).count();
        audit.record("QA_CHECKED", "resource", c.resource.id(), Map.of("version", c.version.versionNo(), "issues", added));
        return added + " new issue" + (added == 1 ? "" : "s") + (proposals > 0 ? " (" + proposals + " with a proposed correction)" : "")
                + (rechecked[0] > 0 ? "; rechecked " + rechecked[0] + ", " + rechecked[1] + " fixed" : "")
                + (critical > 0 ? "; " + critical + " critical open" : "");
    }

    String recheckRun(Ctx c) {
        UUID runId = UUID.randomUUID();
        int[] r = recheck(c, runId, true);
        return "Rechecked " + r[0] + " issue" + (r[0] == 1 ? "" : "s") + ": " + r[1] + " fixed, " + (r[0] - r[1]) + " still open";
    }

    /**
     * Rechecks every open or corrected issue against this version: rules are run again,
     * keys are answered blind again, and the rest go to the model. Returns {rechecked, fixed}.
     */
    private int[] recheck(Ctx c, UUID runId, boolean recordRun) {
        List<IssueRow> open = store.issues(c.resource.id()).stream().filter(i -> Qa.open(i.status())).toList();
        if (open.isEmpty()) return new int[]{0, 0};
        Map<UUID, Boolean> fixed = new LinkedHashMap<>();
        Map<UUID, String> notes = new HashMap<>();
        List<Finding> rulesNow;
        List<Block> blocks = null;
        List<Question> qs = null;
        if (c.resource.kind() == Kind.NOTES) {
            Notes n = NotesRules.read(c.content);
            blocks = QaRules.blocks(n.doc());
            rulesNow = QaRules.notesFindings(n, c.subtopics);
        } else {
            Bank b = McqRules.read(c.content);
            qs = McqRules.list(b.questions());
            List<Bank> others = reviewStore.newestOfKind(c.resource.projectId(), Kind.MCQ).stream()
                    .filter(v -> !v.resourceId().equals(c.resource.id())).map(v -> McqRules.read(MaterialsService.read(v.content()))).toList();
            rulesNow = QaRules.bankFindings(b, others);
        }
        List<IssueRow> keys = new ArrayList<>(), ai = new ArrayList<>();
        for (IssueRow i : open) {
            String rule = QaRules.ruleOf(i.evidence());
            if (rule != null) {
                boolean ok = QaRules.ruleFixed(i.itemRef(), i.type(), rule, rulesNow);
                fixed.put(i.id(), ok);
                notes.put(i.id(), ok ? "The rule no longer finds it." : "The rule still finds it.");
            } else if (qs != null && (i.type().equals("WRONG_KEY") || i.type().equals("AMBIGUOUS"))) {
                keys.add(i);
            } else {
                ai.add(i);
            }
        }
        if (!keys.isEmpty()) {
            List<Question> ask = new ArrayList<>();
            for (IssueRow i : keys) {
                final List<Question> all = qs;
                all.stream().filter(q -> q.id().equals(i.itemRef())).findFirst().ifPresent(ask::add);
            }
            Map<Integer, Answer> answers = ask.isEmpty() ? Map.of() : QaAi.readAnswers(textAi.askJson(c.account, c.model, QaAi.answerSystem(),
                    QaAi.answerPrompt(c.topicTitle, c.notesText, c.passages, ask)), c.passages);
            for (IssueRow i : keys) {
                int k = indexOf(ask, i.itemRef());
                if (k < 0) {
                    fixed.put(i.id(), true);
                    notes.put(i.id(), "The question was removed.");
                    continue;
                }
                Question q = ask.get(k);
                Answer a = answers.get(k + 1);
                boolean ok = a != null && a.answer() != null && Objects.equals(a.answer(), q.answer()) && !a.ambiguous()
                        && a.alsoDefensible().stream().allMatch(x -> x.equals(q.answer()));
                fixed.put(i.id(), ok);
                notes.put(i.id(), a == null || a.answer() == null ? "No answer from the checker." : "The checker now answers "
                        + QaAi.LETTERS[a.answer()] + (q.answer() == null ? ", and no key is marked." : "; the key is " + QaAi.LETTERS[q.answer()] + ".")
                        + (a.ambiguous() ? " It still finds the question ambiguous." : ""));
            }
        }
        if (!ai.isEmpty()) {
            List<String[]> list = new ArrayList<>();
            for (IssueRow i : ai) {
                String now = c.resource.kind() == Kind.NOTES ? currentNotesText(blocks, i.itemRef(), i.original()) : questionText(qs, i.itemRef());
                list.add(new String[]{i.type(), i.explanation(), i.itemLabel(), now});
            }
            Map<Integer, Verdict> v = QaAi.readVerdicts(textAi.askJson(c.account, c.model, QaAi.recheckSystem(),
                    QaAi.issuesPrompt(c.topicTitle, c.passages, list)), "fixed");
            for (int k = 0; k < ai.size(); k++) {
                Verdict x = v.get(k + 1);
                fixed.put(ai.get(k).id(), x != null && x.yes());
                notes.put(ai.get(k).id(), x == null ? "The checker gave no verdict, so it stays open." : x.note());
            }
        }
        int done = 0;
        for (Map.Entry<UUID, Boolean> e : fixed.entrySet()) {
            ObjectNode r = JSON.createObjectNode();
            r.put("runId", runId.toString()).put("versionId", c.version.id().toString()).put("versionNo", c.version.versionNo())
                    .put("fixed", e.getValue()).put("note", notes.get(e.getKey())).put("model", c.model).put("at", Instant.now().toString());
            store.recheck(e.getKey(), e.getValue(), write(r));
            if (e.getValue()) done++;
        }
        if (recordRun) {
            Map<String, Tally> cats = new LinkedHashMap<>();
            RunResult result = new RunResult(cats, null, null, List.of(), "Rechecked " + fixed.size() + ", " + done + " fixed");
            store.insertRun(runId, c.resource.projectId(), c.resource.id(), c.version.id(), c.version.contentHash(), c.job.id(), "RECHECK",
                    c.model, c.provider, null,
                    c.writerModel, independent(c), write(result));
        }
        audit.record("QA_RECHECKED", "resource", c.resource.id(), Map.of("version", c.version.versionNo(), "rechecked", fixed.size(), "fixed", done));
        return new int[]{fixed.size(), done};
    }

    /** A second model's view of each finding, when one is set up. */
    private List<Finding> confirm(Ctx c, List<Finding> found, java.util.function.Function<Finding, String> now) {
        if (c.secondAccount == null || found.isEmpty()) return found;
        List<String[]> list = new ArrayList<>();
        for (Finding f : found) list.add(new String[]{f.type(), f.explanation(), f.itemLabel(), now.apply(f)});
        Map<Integer, Verdict> v = QaAi.readVerdicts(textAi.askJson(c.secondAccount, c.secondModel, QaAi.confirmSystem(),
                QaAi.issuesPrompt(c.topicTitle, c.passages, list)), "agrees");
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            Verdict x = v.get(i + 1);
            out.add(x == null ? found.get(i) : found.get(i).withConfirmations(List.of(new Confirmation(c.secondModel, x.yes(), x.note()))));
        }
        return out;
    }

    static List<Finding> dedupe(List<Finding> found) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : found) {
            if (out.stream().noneMatch(o -> o.type().equals(f.type()) && Objects.equals(o.itemRef(), f.itemRef())
                    && Objects.equals(o.original(), f.original()) && Objects.equals(QaRules.ruleOf(JSON.valueToTree(o.evidence())),
                    QaRules.ruleOf(JSON.valueToTree(f.evidence()))))) {
                out.add(f);
            }
        }
        return out;
    }

    /** Whether a new finding is the same as an issue still open (so it isn't recorded twice). */
    static boolean same(IssueRow i, Finding f) {
        if (!i.type().equals(f.type())) return false;
        String a = QaRules.ruleOf(i.evidence()), b = QaRules.ruleOf(JSON.valueToTree(f.evidence()));
        if (a != null || b != null) return Objects.equals(a, b) && Objects.equals(i.itemRef(), f.itemRef());
        if (i.original() != null && f.original() != null) return McqRules.key(i.original()).equals(McqRules.key(f.original()));
        return Objects.equals(i.itemRef(), f.itemRef());
    }

    static String currentNotesText(List<Block> blocks, String ref, String original) {
        if (original != null && !original.isBlank()) {
            String key = McqRules.key(original);
            for (Block b : blocks) if (McqRules.key(b.text()).contains(key)) return b.text();
        }
        Block at = blocks.stream().filter(b -> b.ref().equals(ref)).findFirst().orElse(null);
        if (at == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (Objects.equals(b.heading(), at.heading())) sb.append(b.text()).append("\n");
            if (sb.length() > 3000) break;
        }
        return sb.toString().strip();
    }

    static String questionText(List<Question> qs, String id) {
        if (qs == null) return null;
        for (int i = 0; i < qs.size(); i++) {
            Question q = qs.get(i);
            if (!q.id().equals(id)) continue;
            StringBuilder sb = new StringBuilder("Question " + (i + 1) + ": " + q.stem());
            List<String> o = q.options() == null ? List.of() : q.options();
            for (int k = 0; k < o.size() && k < 4; k++) sb.append(" ").append(QaAi.LETTERS[k]).append(") ").append(o.get(k));
            if (q.answer() != null && q.answer() >= 0 && q.answer() < 4) sb.append(" Key: ").append(QaAi.LETTERS[q.answer()]).append('.');
            if (q.explanation() != null) sb.append(" Explanation: ").append(q.explanation());
            return sb.toString();
        }
        return null;
    }

    private static int indexOf(List<Question> qs, String id) {
        for (int i = 0; i < qs.size(); i++) if (qs.get(i).id().equals(id)) return i;
        return -1;
    }

    static boolean independent(Ctx c) {
        return c.writerModel == null || !c.writerModel.equalsIgnoreCase(c.model);
    }

    // ---- decisions on issues -----------------------------------------------------------------------

    /**
     * Accepts a proposed correction: it is applied to the draft (or a new draft from the
     * newest version), recorded, and the issue waits for a recheck.
     */
    @Transactional
    public ResourceQa accept(UUID correctionId, Decision d) {
        CorrectionRow corr = store.correction(correctionId).orElseThrow(() -> ApiException.notFound("Correction"));
        IssueRow issue = store.issue(corr.issueId()).orElseThrow(() -> ApiException.notFound("Issue"));
        ResourceRow r = checkable(issue.resourceId());
        projects.requireAccess(r.projectId(), true, "Only the project's owners and editors accept corrections: they change the content");
        if (!"PROPOSED".equals(corr.status())) throw new ApiException(ErrorCode.CONFLICT, "This correction was already decided.");
        if (!Qa.open(issue.status())) throw new ApiException(ErrorCode.CONFLICT, "This issue isn't open any more.");
        VersionRow newest = newest(r);
        if (newest.status() == Status.IN_REVIEW) {
            throw new ApiException(ErrorCode.CONFLICT, "Version " + newest.versionNo() + " is in review. Withdraw it first to change it.");
        }
        JsonNode current = MaterialsService.read(reviewStore.version(newest.id()).orElseThrow().content());
        JsonNode changed;
        if (r.kind() == Kind.NOTES) {
            Notes n = NotesRules.read(current);
            JsonNode doc = QaRules.applyToNotes(n.doc(), corr.proposal().path("find").asText(null), corr.proposal().path("replace").asText(null));
            changed = JSON.valueToTree(new Notes(n.topicId(), n.topicTitle(), n.level(), n.topicsVersionId(), doc, n.references(), n.model()));
        } else {
            changed = JSON.valueToTree(QaRules.applyToBank(McqRules.read(current), corr.proposal()));
        }
        String note = "Accepted the correction for QA issue " + issue.number() + (d == null || blank(d.note()) ? "" : ": " + d.note().strip());
        VersionRow applied = newest.status() == Status.DRAFT ? reviews.replaceDraft(newest, changed, note)
                : reviews.createVersion(new Target(r.projectId(), r.kind(), r.topicId(), r.level(), null, List.of()), changed, Origin.EDIT,
                newest.id(), newest.dependsOn(), note);
        if (store.decideCorrection(corr.id(), "ACCEPTED", applied.id(), d == null ? null : blankToNull(d.note())) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "This correction was decided meanwhile.");
        }
        store.markCorrected(issue.id(), "Correction accepted in version " + applied.versionNo() + "; recheck to close it");
        audit.record("QA_CORRECTION_ACCEPTED", "resource", r.id(), Map.of("issue", issue.number(), "version", applied.versionNo()));
        return forResource(r.id());
    }

    @Transactional
    public ResourceQa reject(UUID correctionId, Decision d) {
        CorrectionRow corr = store.correction(correctionId).orElseThrow(() -> ApiException.notFound("Correction"));
        IssueRow issue = store.issue(corr.issueId()).orElseThrow(() -> ApiException.notFound("Issue"));
        ResourceRow r = checkable(issue.resourceId());
        projects.requireAccess(r.projectId(), false, null);
        if (!projects.canEdit(r.projectId()) && !projects.canReview(r.projectId())) throw new ApiException(ErrorCode.FORBIDDEN, WHO);
        if (store.decideCorrection(corr.id(), "REJECTED", null, d == null ? null : blankToNull(d.note())) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "This correction was already decided.");
        }
        audit.record("QA_CORRECTION_REJECTED", "resource", r.id(), Map.of("issue", issue.number()));
        return forResource(r.id());
    }

    @Transactional
    public ResourceQa dismiss(UUID issueId, Reason reason) {
        IssueRow issue = store.issue(issueId).orElseThrow(() -> ApiException.notFound("Issue"));
        ResourceRow r = checkable(issue.resourceId());
        projects.requireAccess(r.projectId(), false, null);
        if (!projects.canReview(r.projectId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the project's owners and reviewers dismiss QA issues");
        }
        if (store.dismiss(issueId, reason.reason().strip()) == 0) throw new ApiException(ErrorCode.CONFLICT, "This issue isn't open.");
        audit.record("QA_ISSUE_DISMISSED", "resource", r.id(), Map.of("issue", issue.number(), "severity", issue.severity()));
        return forResource(r.id());
    }

    @Transactional
    public ResourceQa reopen(UUID issueId, Reason reason) {
        IssueRow issue = store.issue(issueId).orElseThrow(() -> ApiException.notFound("Issue"));
        ResourceRow r = checkable(issue.resourceId());
        projects.requireAccess(r.projectId(), false, null);
        if (!projects.canReview(r.projectId())) throw new ApiException(ErrorCode.FORBIDDEN, "Only the project's owners and reviewers reopen QA issues");
        if (store.reopen(issueId, reason.reason().strip()) == 0) throw new ApiException(ErrorCode.CONFLICT, "Only a dismissed or resolved issue can be reopened.");
        audit.record("QA_ISSUE_REOPENED", "resource", r.id(), Map.of("issue", issue.number()));
        return forResource(r.id());
    }

    // ---- reports -----------------------------------------------------------------------------------

    /** Makes (or makes again) the topic's QA report from what QA has found so far. */
    @Transactional
    public ResourceState makeReport(UUID projectId, String topicId) {
        projects.requireAccess(projectId, true, "Only the project's owners and editors make QA reports");
        VersionRow topics = reviews.approved(projectId, Kind.TOPICS)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Approve the topic list first."));
        JsonNode t = MaterialsService.read(topics.content());
        TopicBrief brief = materials.brief(projectId, t, topicId);
        List<QaReport.Source> sources = new ArrayList<>();
        List<UUID> covers = new ArrayList<>();
        List<ResourceRow> rs = new ArrayList<>();
        reviewStore.resources(projectId, Kind.NOTES).stream().filter(r -> topicId.equals(r.topicId()))
                .sorted((a, b) -> Integer.compare(levelRank(a.level()), levelRank(b.level()))).forEach(rs::add);
        reviewStore.resources(projectId, Kind.MCQ).stream().filter(r -> topicId.equals(r.topicId())).forEach(rs::add);
        for (ResourceRow r : rs) {
            VersionRow v = reviewStore.approved(r.id()).orElseGet(() -> newest(r));
            if (v == null) continue;
            VersionRow full = reviewStore.version(v.id()).orElseThrow();
            JsonNode content = MaterialsService.read(full.content());
            List<RunRow> runs = store.runs(r.id());
            Map<UUID, String> hashes = new HashMap<>();
            for (RunRow run : runs) hashes.put(run.id(), run.contentHash());
            String label, text = null;
            Map<String, Integer> difficulty = null;
            Integer questions = null;
            if (r.kind() == Kind.NOTES) {
                label = Learning.levelLabel(r.level()) + " notes";
                text = NoteDoc.plainText(NotesRules.read(content).doc());
            } else {
                label = "Question bank";
                List<Question> qs = McqRules.list(McqRules.read(content).questions());
                questions = qs.size();
                difficulty = new LinkedHashMap<>();
                for (Question q : qs) difficulty.merge(q.difficulty() == null ? "NOT_SET" : q.difficulty(), 1, Integer::sum);
            }
            sources.add(new QaReport.Source(r.id(), r.kind().name(), r.level(), label, full.id(), full.versionNo(), full.status().name(),
                    full.contentHash(), text, runs, hashes, store.issues(r.id()), store.corrections(r.id()), difficulty, questions));
            covers.add(r.id());
        }
        if (sources.isEmpty()) throw new ApiException(ErrorCode.CONFLICT, "This topic has no notes or questions to report on yet.");
        QaReport.Report report = QaReport.build(topicId, brief.title(), Instant.now().toString(), sources, brief.subtopics());
        JsonNode content = JSON.valueToTree(report);
        Target target = new Target(projectId, Kind.QA, topicId, null, brief.title() + ": QA report", covers);
        Optional<ResourceRow> existing = reviewStore.resource(projectId, Kind.QA, topicId, null);
        VersionRow made;
        if (existing.isPresent()) {
            VersionRow newest = newest(existing.get());
            covers.forEach(c -> reviewStore.link(existing.get().id(), c));
            if (newest.status() == Status.IN_REVIEW) {
                throw new ApiException(ErrorCode.CONFLICT, "Version " + newest.versionNo() + " of the report is in review. Withdraw it first.");
            }
            made = newest.status() == Status.DRAFT ? reviews.replaceDraft(newest, content, "Made again from the QA results")
                    : reviews.createVersion(target, content, Origin.MANUAL, newest.id(), null, "Made again from the QA results");
        } else {
            made = reviews.createVersion(target, content, Origin.MANUAL, null, null, "Made from the QA results");
        }
        audit.record("QA_REPORT_MADE", "resource", made.resourceId(), Map.of("headline", report.headline()));
        return reviews.stateOf(made.resourceId());
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private ResourceRow checkable(UUID resourceId) {
        ResourceRow r = reviewStore.resource(resourceId).orElseThrow(() -> ApiException.notFound("Resource"));
        if (r.kind() != Kind.NOTES && r.kind() != Kind.MCQ) throw ApiException.badRequest("QA checks notes and question banks");
        return r;
    }

    private VersionRow newest(ResourceRow r) {
        List<VersionRow> vs = reviewStore.versions(r.id());
        return vs.isEmpty() ? null : vs.get(0);
    }

    /** The model that wrote it: recorded in notes; for a bank, the model of its latest AI job. */
    private String writerModel(ResourceRow r, VersionRow v) {
        if (v == null) return null;
        if (r.kind() == Kind.NOTES) {
            return reviewStore.version(v.id()).map(x -> NotesRules.read(MaterialsService.read(x.content())).model()).orElse(null);
        }
        return jobs.recent(r.projectId(), "MCQ").stream().filter(j -> j.target().equals(r.topicId()) && "SUCCEEDED".equals(j.status()))
                .findFirst().map(j -> MaterialsService.read(j.input()).path("model").asText(null)).orElse(null);
    }

    static int levelRank(String level) {
        int i = level == null ? -1 : Learning.LEVELS.indexOf(level);
        return i < 0 ? 99 : i;
    }

    private static JobInfo info(JobRow j) {
        return new JobInfo(j.id(), j.status(), j.error(), j.summary(), j.createdAt(), j.createdBy());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.strip();
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
