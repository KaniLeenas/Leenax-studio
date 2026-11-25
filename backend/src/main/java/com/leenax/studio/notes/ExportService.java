package com.leenax.studio.notes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.ReviewStore;
import com.leenax.studio.approvals.ReviewStore.ResourceRow;
import com.leenax.studio.approvals.ReviewStore.VersionRow;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.media.storage.ObjectStorage;
import com.leenax.studio.notes.Learning.Bank;
import com.leenax.studio.notes.Learning.Notes;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.projects.Project;
import com.leenax.studio.slides.Slides;
import com.leenax.studio.slides.SlidesRules;
import com.leenax.studio.projects.ProjectService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Exports (E4): notes as PDF or Word; question banks and quizzes as PDF, Word, CSV or
 * Excel. The exact stored version is sent to the private document service, which builds
 * the file; each file names the version and its review status.
 */
@Service
public class ExportService {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> NOTES_FORMATS = Set.of("pdf", "docx");
    static final Set<String> QUESTION_FORMATS = Set.of("pdf", "docx", "csv", "xlsx");
    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    static final int MAX_SVG_CHARS = 600_000;
    static final Pattern SVG_START = Pattern.compile("^\\s*(<\\?xml[^>]*>\\s*)?<svg[\\s>]");

    public record ExportFile(String filename, String contentType, byte[] bytes) {
    }

    public record FigureRequest(@NotNull String kind, @NotBlank @Size(max = 20_000) String source, @NotBlank String svg) {
    }

    private final ReviewStore reviews;
    private final ProjectService projects;
    private final QuizService quizzes;
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final String docservice;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ExportService(ReviewStore reviews, ProjectService projects, QuizService quizzes, JdbcTemplate jdbc, ObjectStorage storage,
                         @Value("${leenax.docservice.url:}") String docservice) {
        this.reviews = reviews;
        this.projects = projects;
        this.quizzes = quizzes;
        this.jdbc = jdbc;
        this.storage = storage;
        this.docservice = docservice == null ? "" : docservice.replaceAll("/+$", "");
    }

    // ---- figures -----------------------------------------------------------------------------------

    /**
     * Keeps the browser's drawing of an equation or diagram, for exports. The document
     * service cleans every drawing again before using it.
     */
    public void saveFigure(UUID projectId, FigureRequest req) {
        projects.requireAccess(projectId, false, null);
        if (!projects.canEdit(projectId) && !projects.canReview(projectId)) return; // viewers' drawings aren't kept
        String kind = req.kind().toUpperCase(Locale.ROOT);
        if (!kind.equals("MATH") && !kind.equals("MERMAID")) throw ApiException.badRequest("Unknown kind of drawing");
        if (req.svg().length() > MAX_SVG_CHARS || !SVG_START.matcher(req.svg()).find()) throw ApiException.badRequest("Not an SVG drawing");
        jdbc.update("insert into note_figures (project_id, kind, source_hash, svg) values (?, ?, ?, ?) on conflict do nothing",
                projectId, kind, NoteDoc.sha256(req.source().strip()), req.svg());
    }

    // ---- notes and banks ---------------------------------------------------------------------------

    public ExportFile version(UUID versionId, String format) {
        return version(versionId, format, "COMBINED");
    }

    public ExportFile version(UUID versionId, String format, String part) {
        VersionRow v = reviews.version(versionId).orElseThrow(() -> ApiException.notFound("Version"));
        ResourceRow r = reviews.resource(v.resourceId()).orElseThrow();
        Project p = projects.requireAccess(r.projectId(), false, null);
        String f = format.toLowerCase(Locale.ROOT);
        JsonNode content = MaterialsService.read(v.content());
        ObjectNode payload = JSON.createObjectNode();
        payload.put("format", f);
        ArrayNode meta = payload.putArray("meta");
        meta(meta, "Subject", subject(p));
        meta(meta, "Version", v.versionNo() + ", " + label(v.status().name()));
        meta(meta, "Exported", LocalDate.now(ZoneId.of("Asia/Colombo")).toString());
        if (r.kind() == Kind.NOTES) {
            if (!NOTES_FORMATS.contains(f)) throw ApiException.badRequest("Notes export as PDF or Word");
            Notes n = NotesRules.read(content);
            payload.put("kind", "notes");
            payload.put("title", n.topicTitle());
            payload.put("subtitle", Learning.levelLabel(n.level()) + " notes");
            payload.set("doc", n.doc());
            payload.set("figures", figures(r.projectId(), n.doc()));
            payload.set("images", images(n.doc()));
            ArrayNode refs = payload.putArray("references");
            (n.references() == null ? List.<Learning.Reference>of() : n.references()).forEach(x -> refs.add(x.label()));
            return export(payload, slug(n.topicTitle()) + "-" + n.level().toLowerCase(Locale.ROOT) + "-v" + v.versionNo(), f);
        }
        if (r.kind() == Kind.MCQ) {
            if (!QUESTION_FORMATS.contains(f)) throw ApiException.badRequest("Question banks export as PDF, Word, CSV or Excel");
            Bank b = McqRules.read(content);
            payload.put("kind", "questions");
            payload.put("title", b.topicTitle() + ": question bank");
            payload.put("answerKey", "inline");
            ArrayNode qs = payload.putArray("questions");
            int i = 1;
            for (Question q : McqRules.list(b.questions())) {
                ObjectNode o = (ObjectNode) JSON.valueToTree(q);
                o.put("number", i++);
                o.put("topic", b.topicTitle());
                o.put("source", q.source() == null ? null : q.source().label());
                qs.add(o);
            }
            return export(payload, slug(b.topicTitle()) + "-questions-v" + v.versionNo(), f);
        }
        if (r.kind() == Kind.QA) {
            if (!NOTES_FORMATS.contains(f)) throw ApiException.badRequest("QA reports export as PDF or Word");
            String which = part == null ? "COMBINED" : part.toUpperCase(Locale.ROOT);
            if (!List.of("NOTES", "MCQ", "COMBINED").contains(which)) throw ApiException.badRequest("Choose the notes, questions or combined report");
            payload.put("kind", "qa");
            payload.put("part", which);
            payload.put("title", content.path("topicTitle").asText() + ": " + switch (which) {
                case "NOTES" -> "notes QA report";
                case "MCQ" -> "MCQ QA report";
                default -> "academic QA report";
            });
            payload.set("report", content);
            return export(payload, slug(content.path("topicTitle").asText()) + "-qa-" + which.toLowerCase(Locale.ROOT) + "-v" + v.versionNo(), f);
        }
        if (r.kind() == Kind.SEO) {
            if (!List.of("pdf", "docx", "xlsx").contains(f)) throw ApiException.badRequest("SEO reports export as PDF, Word or Excel");
            String research = jdbc.queryForList("select data::text from seo_research where id = ?", String.class,
                    java.util.UUID.fromString(content.path("researchId").asText())).stream().findFirst()
                    .orElseThrow(() -> ApiException.notFound("SEO research"));
            payload.put("kind", "seo");
            payload.put("title", content.path("topicTitle").asText() + ": YouTube SEO report");
            payload.set("report", content);
            payload.set("research", MaterialsService.read(research));
            return export(payload, slug(content.path("topicTitle").asText()) + "-seo-v" + v.versionNo(), f);
        }
        if (r.kind() == Kind.SLIDES) {
            if (!List.of("pptx", "pdf").contains(f)) throw ApiException.badRequest("Presentations export as PowerPoint or PDF");
            Slides.Deck d = SlidesRules.read(content);
            ObjectNode figures = JSON.createObjectNode();
            List<String> imageIds = new java.util.ArrayList<>();
            for (Slides.Slide s : Slides.list(d.slides())) {
                if (s.figure() != null) {
                    String hash = NoteDoc.sha256(s.figure().source().strip());
                    figureSvg(r.projectId(), s.figure().kind(), hash).ifPresent(svg -> figures.put(s.figure().kind() + ":" + hash, svg));
                }
                if (s.image() != null) imageIds.add(s.image().assetId().toString());
            }
            payload.put("kind", "slides");
            payload.set("deck", content);
            payload.set("figures", figures);
            payload.set("images", images(imageIds));
            return export(payload, slug(d.topicTitle()) + "-" + d.level().toLowerCase(Locale.ROOT) + "-slides-v" + v.versionNo(), f);
        }
        throw ApiException.badRequest("Only notes, question banks, QA reports, SEO reports and presentations are exported here");
    }

    /** answers: "end" puts the answer key after the questions (a separate page in PDF and Word), "none" leaves it out. */
    public ExportFile quiz(UUID quizId, String format, String answers) {
        UUID projectId = quizzes.projectOf(quizId);
        Project p = projects.requireAccess(projectId, false, null);
        String f = format.toLowerCase(Locale.ROOT);
        if (!QUESTION_FORMATS.contains(f)) throw ApiException.badRequest("Quizzes export as PDF, Word, CSV or Excel");
        QuizService.Quiz q = quizzes.get(quizId);
        ObjectNode payload = JSON.createObjectNode();
        payload.put("format", f);
        payload.put("kind", "questions");
        payload.put("title", q.title());
        payload.put("answerKey", "none".equals(answers) ? "none" : "end");
        ArrayNode meta = payload.putArray("meta");
        meta(meta, "Subject", subject(p));
        meta(meta, "Questions", String.valueOf(q.questions().size()));
        meta(meta, "Quiz number", String.valueOf(q.seed()));
        payload.set("questions", JSON.valueToTree(q.questions()));
        return export(payload, slug(q.title()), f);
    }

    // ---- building ----------------------------------------------------------------------------------

    private ObjectNode figures(UUID projectId, JsonNode doc) {
        ObjectNode out = JSON.createObjectNode();
        for (NoteDoc.Figure fig : NoteDoc.figures(doc)) {
            figureSvg(projectId, fig.kind(), fig.hash()).ifPresent(svg -> out.put(fig.kind() + ":" + fig.hash(), svg));
        }
        return out;
    }

    private java.util.Optional<String> figureSvg(UUID projectId, String kind, String hash) {
        return jdbc.queryForList("select svg from note_figures where project_id = ? and kind = ? and source_hash = ?", String.class,
                projectId, kind, hash).stream().findFirst();
    }

    /** Images from the media library, as data URLs; ones you can't see or that aren't images are left out. */
    private ObjectNode images(JsonNode doc) {
        return images(NoteDoc.imageIds(doc));
    }

    private ObjectNode images(List<String> ids) {
        ObjectNode out = JSON.createObjectNode();
        for (String id : ids) {
            List<String[]> rows = jdbc.query("select storage_key, content_type, size_bytes from media_assets where id = ?",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2), Long.toString(rs.getLong(3))}, UUID.fromString(id));
            if (rows.isEmpty()) continue;
            String type = rows.get(0)[1].toLowerCase(Locale.ROOT);
            long size = Long.parseLong(rows.get(0)[2]);
            if (!IMAGE_TYPES.contains(type) || size > MAX_IMAGE_BYTES) continue;
            byte[] bytes = storage.read(rows.get(0)[0], MAX_IMAGE_BYTES);
            out.put(id, "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes));
        }
        return out;
    }

    private ExportFile export(ObjectNode payload, String name, String format) {
        if (docservice.isBlank()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Exports need the document service, which isn't running on this server");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(docservice + "/export"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 422) {
                JsonNode detail = JSON.readTree(res.body()).path("detail");
                List<String> problems = new java.util.ArrayList<>();
                detail.path("problems").forEach(x -> problems.add(x.asText()));
                throw new ApiException(ErrorCode.CONFLICT, detail.path("message").asText("The export was refused") + (problems.isEmpty() ? ""
                        : ": " + String.join("; ", problems.stream().limit(3).toList())));
            }
            if (res.statusCode() != 200) {
                String detail = new String(res.body(), StandardCharsets.UTF_8);
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The export failed: " + (detail.length() > 200 ? detail.substring(0, 200) : detail));
            }
            return new ExportFile(name + "." + format, contentType(format), res.body());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The document service isn't reachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Interrupted");
        }
    }

    static String contentType(String format) {
        return switch (format) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "csv" -> "text/csv; charset=utf-8";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }

    /** A file name from a title: letters (any script), digits and dashes. */
    static String slug(String s) {
        String t = (s == null ? "export" : s).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        if (t.isEmpty()) t = "export";
        return t.length() <= 60 ? t : t.substring(0, 60).replaceAll("-+$", "");
    }

    private static String subject(Project p) {
        String code = p.getModuleCode();
        return code == null || code.isBlank() ? p.getName() : code + ", " + p.getName();
    }

    private static String label(String status) {
        return switch (status) {
            case "IN_REVIEW" -> "in review";
            case "CHANGES_REQUESTED" -> "changes requested";
            default -> status.toLowerCase(Locale.ROOT);
        };
    }

    private static void meta(ArrayNode meta, String k, String v) {
        meta.addArray().add(k).add(v);
    }
}
