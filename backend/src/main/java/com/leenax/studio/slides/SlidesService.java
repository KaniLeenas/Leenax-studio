package com.leenax.studio.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.leenax.studio.notes.MaterialsAi.TopicBrief;
import com.leenax.studio.notes.MaterialsService;
import com.leenax.studio.notes.NoteDoc;
import com.leenax.studio.notes.NotesRules;
import com.leenax.studio.projects.Project;
import com.leenax.studio.projects.ProjectService;
import com.leenax.studio.seo.SeoReportRules;
import com.leenax.studio.slides.Slides.Deck;
import com.leenax.studio.slides.Slides.Slide;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.leenax.studio.topicanalysis.AnalysisPlans;
import com.leenax.studio.topicanalysis.TopicAnalysis;
import com.leenax.studio.topicanalysis.TopicAnalysisService;

/**
 * The presentation studio (E7). A deck is made from a topic's approved notes at one level, and
 * only once the topic's academic QA report and SEO report are approved. Making a deck is a
 * background job; redoing one slide is immediate and returns a proposal that the person keeps
 * or discards (nothing is saved until they save).
 */
@Service
public class SlidesService implements AnalysisWorker.Runner {

    private static final Logger log = LoggerFactory.getLogger(SlidesService.class);
    static final ObjectMapper JSON = new ObjectMapper();

    public record GenerateRequest(@NotBlank @Size(max = 40) String topicId, @NotBlank @Size(max = 12) String level, @NotNull UUID accountId,
                                  @NotBlank @Size(max = 100) String model, @Size(max = 20) String style, @Min(5) @Max(40) Integer slides,
                                  @Size(max = 2000) String guidance) {
    }

    public record RegenerateRequest(@NotNull UUID versionId, @NotNull UUID accountId, @NotBlank @Size(max = 100) String model,
                                    @Size(max = 2000) String guidance) {
    }

    /** A proposed slide and how its text fits; it isn't saved. */
    public record Proposal(Slide slide, JsonNode fit) {
    }

    public record DeckInfo(String level, UUID resourceId, Integer versionNo, String status, int slides, boolean running, String error) {
    }

    public record TopicSlides(String topicId, String title, int depth, List<String> approvedNotes, boolean qaApproved, boolean seoApproved,
                              List<DeckInfo> decks) {
    }

    public record Overview(boolean topicsApproved, List<TopicSlides> topics, boolean canEdit, Map<String, String> styles, List<String> types,
                           List<String> layouts) {
    }

    private final ReviewService reviews;
    private final ReviewStore reviewStore;
    private final SlidesStore store;
    private final SlidesRules rules;
    private final SlideFitter fitter;
    private final AnalysisStore jobs;
    private final MaterialsService materials;
    private final ProjectService projects;
    private final TextAi textAi;
    private final AuditService audit;

    public SlidesService(ReviewService reviews, ReviewStore reviewStore, SlidesStore store, SlidesRules rules, SlideFitter fitter, AnalysisStore jobs,
                         MaterialsService materials, ProjectService projects, TextAi textAi, AuditService audit) {
        this.reviews = reviews;
        this.reviewStore = reviewStore;
        this.store = store;
        this.rules = rules;
        this.fitter = fitter;
        this.jobs = jobs;
        this.materials = materials;
        this.projects = projects;
        this.textAi = textAi;
        this.audit = audit;
    }

    // ---- reading -----------------------------------------------------------------------------------

    public Overview overview(UUID projectId) {
        projects.requireAccess(projectId, false, null);
        Optional<VersionRow> topics = reviews.approved(projectId, Kind.TOPICS);
        Map<String, List<String>> notesLevels = new HashMap<>();
        for (ResourceRow r : reviewStore.resources(projectId, Kind.NOTES)) {
            if (reviewStore.approved(r.id()).isPresent()) notesLevels.computeIfAbsent(r.topicId(), k -> new ArrayList<>()).add(r.level());
        }
        Map<String, Boolean> qa = approvedByTopic(projectId, Kind.QA), seo = approvedByTopic(projectId, Kind.SEO);
        Map<String, List<ResourceRow>> decks = new HashMap<>();
        reviewStore.resources(projectId, Kind.SLIDES).forEach(r -> decks.computeIfAbsent(r.topicId(), k -> new ArrayList<>()).add(r));
        Map<String, JobRow> latestJob = new HashMap<>();
        for (JobRow j : jobs.recent(projectId, "SLIDES")) latestJob.putIfAbsent(j.target(), j);
        List<TopicSlides> out = new ArrayList<>();
        for (MaterialsService.TopicRow t : topics.map(v -> MaterialsService.topicRows(MaterialsService.read(v.content()))).orElse(List.of())) {
            List<String> levels = new ArrayList<>(notesLevels.getOrDefault(t.id(), List.of()));
            levels.sort((a, b) -> Learning.LEVELS.indexOf(a) - Learning.LEVELS.indexOf(b));
            List<DeckInfo> infos = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (ResourceRow r : decks.getOrDefault(t.id(), List.of())) {
                VersionRow v = newest(r);
                int count = v == null ? 0 : Slides.list(SlidesRules.read(MaterialsService.read(reviewStore.version(v.id()).orElseThrow().content())).slides()).size();
                JobRow j = latestJob.get(target(t.id(), r.level()));
                infos.add(new DeckInfo(r.level(), r.id(), v == null ? null : v.versionNo(), v == null ? null : v.status().name(), count, running(j),
                        failed(j)));
                seen.add(r.level());
            }
            for (String level : Learning.LEVELS) {
                JobRow j = latestJob.get(target(t.id(), level));
                if (!seen.contains(level) && j != null && (running(j) || failed(j) != null)) {
                    infos.add(new DeckInfo(level, null, null, null, 0, running(j), failed(j)));
                }
            }
            infos.sort((a, b) -> Learning.LEVELS.indexOf(a.level()) - Learning.LEVELS.indexOf(b.level()));
            out.add(new TopicSlides(t.id(), t.title(), t.depth(), levels, qa.getOrDefault(t.id(), false), seo.getOrDefault(t.id(), false), infos));
        }
        return new Overview(topics.isPresent(), out, projects.canEdit(projectId), Slides.STYLES, Slides.TYPES, Slides.LAYOUTS);
    }

    /** How a deck's text fits, while it's being edited (nothing is saved). */
    public JsonNode fit(UUID projectId, JsonNode deck) {
        projects.requireAccess(projectId, false, null);
        return rules.normalise(deck, null).path("fit");
    }

    // ---- making a deck -----------------------------------------------------------------------------

    public JobRow start(UUID projectId, GenerateRequest req) {
        projects.requireAccess(projectId, true, "Only the project's owners and editors make presentations");
        String level = req.level().strip().toUpperCase(java.util.Locale.ROOT);
        if (!Learning.LEVELS.contains(level)) throw ApiException.badRequest("Unknown notes level");
        VersionRow topics = reviews.approved(projectId, Kind.TOPICS)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Approve the topic list first."));
        materials.brief(projectId, MaterialsService.read(topics.content()), req.topicId());
        requireApprovals(projectId, req.topicId(), level);
        reviewStore.resource(projectId, Kind.SLIDES, req.topicId(), level).map(this::newest).filter(v -> v != null && v.status() == Status.IN_REVIEW)
                .ifPresent(v -> {
                    throw new ApiException(ErrorCode.CONFLICT, "This presentation is in review. Withdraw it first, or wait for the decision.");
                });
        String style = req.style() == null || req.style().isBlank() ? "LECTURE" : req.style().strip().toUpperCase(java.util.Locale.ROOT);
        if (!Slides.STYLES.containsKey(style)) throw ApiException.badRequest("Unknown style");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("topicId", req.topicId());
        input.put("level", level);
        input.put("accountId", req.accountId());
        input.put("model", req.model().strip());
        input.put("style", style);
        input.put("slides", req.slides() == null ? 14 : req.slides());
        if (req.guidance() != null && !req.guidance().isBlank()) input.put("guidance", req.guidance().strip());
        UUID id = UUID.randomUUID();
        try {
            jobs.insert(id, projectId, "SLIDES", target(req.topicId(), level), write(input));
        } catch (RuntimeException e) {
            if (SqlStates.find(e).map(x -> "23505".equals(x.getSQLState())).orElse(false)) {
                throw new ApiException(ErrorCode.CONFLICT, "A presentation is already being made for these notes.");
            }
            throw e;
        }
        audit.record("SLIDES_STARTED", "project", projectId, Map.of("topic", req.topicId(), "level", level));
        return jobs.job(id).orElseThrow();
    }

    /** "No presentation before academic and SEO approval." */
    void requireApprovals(UUID projectId, String topicId, String level) {
        List<String> missing = new ArrayList<>();
        if (store.approvedVersion(projectId, Kind.NOTES, topicId, level).isEmpty()) missing.add("the " + Learning.levelLabel(level).toLowerCase(java.util.Locale.ROOT) + " notes");
        if (store.approvedVersion(projectId, Kind.QA, topicId, null).isEmpty()) missing.add("the academic QA report");
        if (store.approvedVersion(projectId, Kind.SEO, topicId, null).isEmpty()) missing.add("the SEO report");
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "Slides are made after approval. Approve " + join(missing) + " for this topic first.");
        }
    }

    @Override
    public boolean handles(String kind) {
        return kind.equals("SLIDES");
    }

    @Override
    public void process(UUID jobId) {
        JobRow j = jobs.job(jobId).orElse(null);
        if (j == null || !(j.status().equals("QUEUED") || j.status().equals("RUNNING"))) return;
        jobs.running(jobId);
        try {
            String summary = run(j, JSON.readTree(j.input()));
            jobs.summary(jobId, summary);
            jobs.succeeded(jobId, null);
        } catch (ProviderException e) {
            if (e.kind().retryable() && j.attempts() + 1 < 2) jobs.retryLater(jobId, e.getMessage() + " — trying again shortly", 120);
            else jobs.failed(jobId, e.getMessage());
        } catch (ApiException e) {
            jobs.failed(jobId, e.getMessage());
        } catch (Exception e) {
            log.error("Slides job {} failed", jobId, e);
            jobs.failed(jobId, "Making the presentation failed unexpectedly");
        }
    }

    String run(JobRow j, JsonNode in) {
        UUID projectId = j.projectId();
        String topicId = in.path("topicId").asText();
        String level = in.path("level").asText();
        requireApprovals(projectId, topicId, level);
        Optional<TopicAnalysis> plan = TopicAnalysisService.approved(reviewStore, projectId, topicId)
                .filter(a -> a.videoPlan() != null && a.videoPlan().scenes() != null && !a.videoPlan().scenes().isEmpty());
        int count = in.path("slides").isInt() ? in.path("slides").asInt() : plan.map(a -> AnalysisPlans.slidesFor(a, 14)).orElse(14);
        Sources src = sources(projectId, topicId, level, count, in.path("guidance").asText(null));
        String system = SlidesAi.deckSystem() + (plan.isPresent() ? SlidesAi.PLANNED : "");
        String prompt = SlidesAi.deckPrompt(src.brief())
                + plan.map(a -> AnalysisPlans.deckPlan(a, AnalysisPlans.wordsPerMinute(src.brief().notesText()))).orElse("");
        JsonNode reply = textAi.askJson(UUID.fromString(in.path("accountId").asText()), in.path("model").asText(), system, prompt);
        List<Slide> slides = new ArrayList<>(SlidesAi.readDeck(reply, src.brief().figures()));
        if (slides.isEmpty()) throw ApiException.badRequest("The AI reply had no slides. Try again or use another model.");
        if (!"TITLE".equals(slides.get(0).type())) {
            slides.add(0, new Slide(SlidesAi.newId(), "TITLE", "TITLE", src.brief().topicTitle(), subtitle(src), null, null, null, null, null, null,
                    null, null, null, null));
        }
        Slide refs = referencesSlide(src.references());
        if (refs != null) slides.add(refs);
        String title = SlidesAi.cut(reply.path("title").asText(null), 200);
        Deck deck = new Deck(topicId, src.brief().topicTitle(), level, src.notesVersion(), src.qaVersion(), src.seoVersion(), in.path("style").asText("LECTURE"),
                title == null ? src.brief().topicTitle() : title, slides, null, in.path("model").asText());
        return write(projectId, topicId, level, src, JSON.valueToTree(deck), "Made with AI (" + in.path("model").asText() + ") from the approved notes"
                + (plan.isPresent() ? ", following the approved topic analysis's video plan" : ""));
    }

    /** The notes' references as a references slide (never the AI's). */
    static Slide referencesSlide(List<String> refs) {
        if (refs.isEmpty()) return null;
        return new Slide(SlidesAi.newId(), "REFERENCES", "BULLETS", "References", null, refs.stream().limit(Slides.MAX_BULLETS).toList(), null, null,
                null, null, null, null, null, null, null);
    }

    private String write(UUID projectId, String topicId, String level, Sources src, JsonNode content, String note) {
        String title = src.brief().topicTitle() + ": " + Learning.levelLabel(level).toLowerCase(java.util.Locale.ROOT) + " slides";
        Target t = new Target(projectId, Kind.SLIDES, topicId, level, title, src.dependsOn());
        Optional<ResourceRow> existing = reviewStore.resource(projectId, Kind.SLIDES, topicId, level);
        if (existing.isPresent()) {
            VersionRow newest = newest(existing.get());
            if (newest != null && newest.status() == Status.IN_REVIEW) return "The presentation is in review, so it wasn't replaced.";
            if (newest != null && newest.status() == Status.DRAFT) {
                reviews.replaceDraft(newest, content, note);
                return "The presentation draft was rewritten.";
            }
            reviews.createVersion(t, content, Origin.AI, newest == null ? null : newest.id(), src.notesVersion(), note);
            return "A new presentation version was made.";
        }
        reviews.createVersion(t, content, Origin.AI, null, src.notesVersion(), note);
        return "The presentation draft was made.";
    }

    // ---- one slide again ---------------------------------------------------------------------------

    public Proposal regenerate(UUID resourceId, String slideId, RegenerateRequest req) {
        ResourceRow r = reviewStore.resource(resourceId).filter(x -> x.kind() == Kind.SLIDES).orElseThrow(() -> ApiException.notFound("Presentation"));
        projects.requireAccess(r.projectId(), true, "Only the project's owners and editors change presentations");
        VersionRow v = reviewStore.version(req.versionId()).filter(x -> x.resourceId().equals(resourceId)).orElseThrow(() -> ApiException.notFound("Version"));
        Deck deck = SlidesRules.read(MaterialsService.read(v.content()));
        List<Slide> slides = Slides.list(deck.slides());
        int at = -1;
        for (int i = 0; i < slides.size(); i++) if (slides.get(i).id().equals(slideId)) at = i;
        if (at < 0) throw ApiException.notFound("Slide");
        Slide current = slides.get(at);
        if ("REFERENCES".equals(current.type())) throw ApiException.badRequest("The references slide lists the notes' references; it isn't written by AI");
        if (store.approvedVersion(r.projectId(), Kind.NOTES, r.topicId(), r.level()).isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "The notes for this presentation aren't approved any more, so slides can't be made from them.");
        }
        Sources src = sources(r.projectId(), r.topicId(), r.level(), slides.size(), req.guidance());
        List<String> outline = slides.stream().map(s -> s.type() + ": " + (s.title() == null ? "" : s.title())).toList();
        JsonNode reply = textAi.askJson(req.accountId(), req.model(), SlidesAi.slideSystem(),
                SlidesAi.slidePrompt(src.brief(), outline, at + 1, JSON.valueToTree(current)));
        Slide made = SlidesAi.readSlide(reply.path("slide"), src.brief().figures(), current.id());
        if (made == null) throw ApiException.badRequest("The AI reply had no slide. Try again or use another model.");
        if (current.image() != null && Slides.fields(made.layout()).contains("image")) {
            made = new Slide(made.id(), made.type(), made.layout(), made.title(), made.subtitle(), made.bullets(), made.term(), made.text(), made.table(),
                    made.figure(), current.image(), made.caption(), made.code(), made.notes(), made.sources());
        }
        Deck one = new Deck(deck.topicId(), deck.topicTitle(), deck.level(), null, null, null, deck.style(), deck.title(), List.of(made), null, null);
        JsonNode fit = fitter.fit(JSON.valueToTree(one));
        audit.record("SLIDE_REGENERATED", "resource", resourceId, Map.of("slide", slideId, "model", req.model()));
        return new Proposal(made, fit);
    }

    // ---- what a deck is made from ------------------------------------------------------------------

    record Sources(SlidesAi.Brief brief, UUID notesVersion, UUID qaVersion, UUID seoVersion, List<String> references, List<UUID> dependsOn,
                   String subject) {
    }

    Sources sources(UUID projectId, String topicId, String level, int slideCount, String guidance) {
        VersionRow topics = reviews.approved(projectId, Kind.TOPICS)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "The topic list is no longer approved."));
        TopicBrief brief = materials.brief(projectId, MaterialsService.read(topics.content()), topicId);
        UUID notesV = store.approvedVersion(projectId, Kind.NOTES, topicId, level).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "The notes aren't approved."));
        UUID qaV = store.approvedVersion(projectId, Kind.QA, topicId, null).orElse(null);
        UUID seoV = store.approvedVersion(projectId, Kind.SEO, topicId, null).orElse(null);
        Learning.Notes notes = NotesRules.read(MaterialsService.read(store.content(notesV).orElseThrow()));
        String text = NoteDoc.plainText(notes.doc());
        if (text.length() > 60_000) text = text.substring(0, 60_000);
        String videoTitle = null;
        List<String> keywords = new ArrayList<>();
        if (seoV != null) {
            JsonNode seo = MaterialsService.read(store.content(seoV).orElseThrow());
            videoTitle = SeoReportRules.chosenText(seo);
            seo.path("primaryKeywords").forEach(k -> keywords.add(k.asText()));
        }
        Project p = projects.requireAccess(projectId, false, null);
        List<UUID> deps = new ArrayList<>();
        for (Kind k : List.of(Kind.NOTES, Kind.QA, Kind.SEO)) {
            reviewStore.resource(projectId, k, topicId, k == Kind.NOTES ? level : null).ifPresent(r -> deps.add(r.id()));
        }
        SlidesAi.Brief b = new SlidesAi.Brief(p.getSubjectName(), brief.title(), brief.path(), Learning.levelLabel(level), text, NoteDoc.headings(notes.doc()),
                NoteDoc.figures(notes.doc()), videoTitle, keywords, Math.max(5, Math.min(40, slideCount)), guidance);
        List<String> refs = Slides.list(notes.references()).stream().map(Learning.Reference::label).filter(x -> x != null && !x.isBlank()).toList();
        return new Sources(b, notesV, qaV, seoV, refs, deps, p.getSubjectName());
    }

    static String subtitle(Sources src) {
        String level = src.brief().levelLabel();
        return src.subject() == null || src.subject().isBlank() ? level : src.subject() + ", " + level.toLowerCase(java.util.Locale.ROOT);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private Map<String, Boolean> approvedByTopic(UUID projectId, Kind kind) {
        Map<String, Boolean> out = new HashMap<>();
        for (ResourceRow r : reviewStore.resources(projectId, kind)) if (reviewStore.approved(r.id()).isPresent()) out.put(r.topicId(), true);
        return out;
    }

    static String target(String topicId, String level) {
        return topicId + ":" + level;
    }

    private static boolean running(JobRow j) {
        return j != null && (j.status().equals("QUEUED") || j.status().equals("RUNNING"));
    }

    private static String failed(JobRow j) {
        return j != null && j.status().equals("FAILED") ? j.error() : null;
    }

    private VersionRow newest(ResourceRow r) {
        List<VersionRow> vs = reviewStore.versions(r.id());
        return vs.isEmpty() ? null : vs.get(0);
    }

    static String join(List<String> parts) {
        if (parts.size() <= 1) return String.join("", parts);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
