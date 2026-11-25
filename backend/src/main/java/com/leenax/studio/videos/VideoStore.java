package com.leenax.studio.videos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.notes.Learning;
import com.leenax.studio.notes.MaterialsService;
import com.leenax.studio.notes.McqRules;
import com.leenax.studio.notes.NoteDoc;
import com.leenax.studio.slides.Slides;
import com.leenax.studio.slides.SlidesRules;
import com.leenax.studio.slides.SlidesStore;
import com.leenax.studio.videos.Videos.Plan;
import com.leenax.studio.videos.Videos.Scene;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The checks on a video that need the database: what it's made from is approved and unchanged
 * on screen, and this exact version was rendered and passed its file checks
 * ("a rendered quiz video passes validation before it can be approved").
 */
@Component
public class VideoStore implements Review.Gate {

    static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final SlidesStore slides;

    public VideoStore(JdbcTemplate jdbc, SlidesStore slides) {
        this.jdbc = jdbc;
        this.slides = slides;
    }

    public Optional<UUID> approved(UUID projectId, Kind kind, String topicId, String level) {
        return slides.approvedVersion(projectId, kind, topicId, level);
    }

    public Optional<String> content(UUID versionId) {
        return slides.content(versionId);
    }

    public Optional<String> figure(UUID projectId, String kind, String source) {
        return jdbc.queryForList("select svg from note_figures where project_id = ? and kind = ? and source_hash = ?", String.class,
                projectId, kind, NoteDoc.sha256(source.strip())).stream().findFirst();
    }

    /** A render of a video version, and what the render service and the file checks said. */
    public record RenderRow(UUID renderJobId, UUID versionId, String contentHash, UUID timelineId, String status, double progress, String error,
                            UUID outputMediaId, JsonNode validation, Instant createdAt, Instant completedAt) {
    }

    static final String RENDERS = "select vr.render_job_id, vr.version_id, vr.content_hash, vr.timeline_id, j.status, j.progress, j.error_message,"
            + " j.output_media_id, j.validation::text, vr.created_at, j.completed_at from video_renders vr join render_jobs j on j.id = vr.render_job_id"
            + " where vr.resource_id = ? order by vr.created_at desc limit 20";

    public List<RenderRow> renders(UUID resourceId) {
        return jdbc.query(RENDERS, (rs, i) -> new RenderRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3).strip(),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getDouble(6), rs.getString(7), rs.getObject(8, UUID.class), json(rs.getString(9)),
                rs.getTimestamp(10).toInstant(), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant()), resourceId);
    }

    public void recordRender(UUID projectId, UUID resourceId, UUID versionId, String contentHash, UUID renderJobId, UUID timelineId) {
        jdbc.update("insert into video_renders (id, project_id, resource_id, version_id, content_hash, render_job_id, timeline_id) values (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), projectId, resourceId, versionId, contentHash, renderJobId, timelineId);
    }

    @Override
    public List<Check> checks(UUID projectId, UUID resourceId, Kind kind, String contentHash) {
        if (kind != Kind.LESSON_VIDEO && kind != Kind.QUIZ_VIDEO) return List.of();
        String content = jdbc.queryForList("select content::text from resource_versions where resource_id = ? and content_hash = ?"
                + " order by version_no desc limit 1", String.class, resourceId, contentHash).stream().findFirst().orElse(null);
        if (content == null) return List.of();
        Plan p = VideoRules.read(MaterialsService.read(content));
        List<Check> out = new ArrayList<>();

        // made from approved content
        if (kind == Kind.LESSON_VIDEO) {
            Optional<UUID> deck = approved(projectId, Kind.SLIDES, p.topicId(), p.level());
            out.add(deck.isPresent() ? Check.pass("source-approved", "The presentation is approved")
                    : Check.fail("source-approved", "The presentation for these notes isn't approved"));
            if (deck.isPresent() && p.sourceVersionId() != null && !deck.get().equals(p.sourceVersionId())) {
                out.add(Check.warn("source-version", "Made from an earlier version of the presentation; the approved one has changed. Make the video again."));
            }
        } else {
            Optional<UUID> mcq = approved(projectId, Kind.MCQ, p.topicId(), null);
            Optional<UUID> seo = approved(projectId, Kind.SEO, p.topicId(), null);
            out.add(mcq.isPresent() ? Check.pass("source-approved", "The MCQ set is approved") : Check.fail("source-approved", "The topic's MCQ set isn't approved"));
            out.add(seo.isPresent() ? Check.pass("seo-approved", "The SEO report is approved") : Check.fail("seo-approved", "The topic's SEO report isn't approved"));
            if (mcq.isPresent() && p.sourceVersionId() != null && !mcq.get().equals(p.sourceVersionId())) {
                out.add(Check.warn("source-version", "Made from an earlier version of the MCQ set; the approved one has changed. Make the video again."));
            }
        }

        // what is shown is what was approved
        if (p.sourceVersionId() != null) {
            List<String> differ = content(p.sourceVersionId()).map(src -> differences(p, MaterialsService.read(src))).orElse(List.of("the source version is missing"));
            out.add(differ.isEmpty() ? Check.pass("matches-source", kind == Kind.LESSON_VIDEO ? "Every slide is as approved" : "Every question is as approved")
                    : Check.fail("matches-source", "Not as approved: " + String.join("; ", differ.stream().limit(5).toList())
                    + ". Change the source and make the video again."));
        }

        // a render of exactly this version, checked
        RenderRow r = renders(resourceId).stream().filter(x -> x.contentHash().equals(contentHash)).findFirst().orElse(null);
        if (r == null) {
            out.add(Check.fail("rendered", "Render this version. It is checked with ffprobe before it counts."));
        } else switch (r.status()) {
            case "QUEUED", "RENDERING" -> out.add(Check.fail("rendered", "Rendering (" + Math.round(r.progress() * 100) + "%)"));
            case "COMPLETED" -> {
                boolean ok = r.validation() != null && r.validation().path("ok").asBoolean(false) && r.outputMediaId() != null;
                out.add(ok ? Check.pass("rendered", "Rendered and checked: " + summary(r.validation()))
                        : Check.fail("rendered", "The render has no passed file check. Render again."));
            }
            case "FAILED" -> out.add(Check.fail("rendered", "The last render failed: " + (r.error() == null ? "unknown reason" : r.error())));
            default -> out.add(Check.fail("rendered", "The last render was cancelled. Render again."));
        }
        return out;
    }

    /** What a file check found, in words: "1920×1080, 4:12, 30 fps, sound". */
    public static String summary(JsonNode v) {
        JsonNode f = v.path("found");
        long s = Math.round(f.path("durationSeconds").asDouble());
        return f.path("width").asInt() + "×" + f.path("height").asInt() + ", " + (s / 60) + ":" + String.format("%02d", s % 60) + ", "
                + Math.round(f.path("fps").asDouble()) + " fps" + (f.hasNonNull("audioCodec") ? ", sound" : "");
    }

    /** Slides or questions shown differently from the approved source (by id). */
    static List<String> differences(Plan p, JsonNode source) {
        List<String> out = new ArrayList<>();
        if (Videos.Kind.LESSON.name().equals(p.kind())) {
            Map<String, JsonNode> approved = new HashMap<>();
            for (Slides.Slide s : Videos.list(SlidesRules.read(source).slides())) approved.put(s.id(), JSON.valueToTree(SlidesRules.clean(s, new HashSet<>())));
            for (Scene sc : Videos.list(p.scenes())) {
                if (sc.slide() == null) continue;
                JsonNode want = approved.get(sc.slide().id());
                if (want == null) out.add("“" + sc.title() + "” isn't in the presentation");
                else if (!want.equals(JSON.valueToTree(sc.slide()))) out.add("“" + sc.title() + "” differs from the presentation");
            }
        } else {
            Map<String, Learning.Question> approved = new HashMap<>();
            for (Learning.Question q : Videos.list(McqRules.read(source).questions())) approved.put(q.id(), q);
            for (Scene sc : Videos.list(p.scenes())) {
                if (sc.question() == null) continue;
                Learning.Question q = approved.get(sc.question().questionId());
                Videos.Question v = sc.question();
                if (q == null) out.add(sc.title() + " isn't in the MCQ set");
                else if (!Objects.equals(norm(q.stem()), norm(v.stem())) || !Objects.equals(q.options().stream().map(VideoStore::norm).toList(),
                        Videos.list(v.options()).stream().map(VideoStore::norm).toList()) || !Objects.equals(q.answer(), v.answer())
                        || !Objects.equals(norm(q.explanation()), norm(v.explanation()))) {
                    out.add(sc.title() + " differs from the MCQ set");
                }
            }
        }
        return out;
    }

    static String norm(String s) {
        return s == null ? null : s.strip().replaceAll("\\s+", " ");
    }

    static JsonNode json(String s) {
        try {
            return s == null ? null : JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }
}
