package com.leenax.studio.scripts;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.ai.text.TextAi;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.projects.Project;
import com.leenax.studio.projects.ProjectRepository;
import com.leenax.studio.scripts.ScriptDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Ideas, scripts, rewrites and per-platform post text, written by the chosen AI text model. */
@Service
public class ScriptService {

    static final Set<String> PLATFORMS = Set.of("YOUTUBE", "FACEBOOK", "INSTAGRAM", "TIKTOK", "LINKEDIN", "X", "PINTEREST",
            "THREADS", "REDDIT", "TELEGRAM", "SNAPCHAT", "WHATSAPP");

    private final ScriptRepository scripts;
    private final ProjectRepository projects;
    private final TextAi ai;
    private final AuditService audit;

    public ScriptService(ScriptRepository scripts, ProjectRepository projects, TextAi ai, AuditService audit) {
        this.scripts = scripts;
        this.projects = projects;
        this.ai = ai;
        this.audit = audit;
    }

    public IdeasResponse ideas(IdeasRequest r) {
        int count = r.count() == null ? 5 : r.count();
        JsonNode reply = ai.askJson(r.writer().accountId(), r.writer().model(), ScriptWriting.ideasSystem(),
                ScriptWriting.ideasUser(r.topic(), type(r.videoType()), r.language(), count, r.audience()));
        try {
            return new IdeasResponse(ScriptWriting.parseIdeas(reply));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("The AI didn't return any ideas. Try again or reword the topic.");
        }
    }

    @Transactional
    public ScriptResponse write(WriteRequest r) {
        checkProject(r.projectId());
        ScriptWriting.VideoType type = type(r.videoType());
        JsonNode reply = ai.askJson(r.writer().accountId(), r.writer().model(), ScriptWriting.scriptSystem(),
                ScriptWriting.scriptUser(r.title(), r.idea(), type, r.language(), r.minutes(), r.tone(), r.notes()));
        ScriptWriting.Written w = parse(reply);
        String title = !w.title().isBlank() ? w.title() : r.title() != null && !r.title().isBlank() ? r.title() : "Untitled script";
        Script s = new Script(title, r.language(), type.name());
        s.setProjectId(r.projectId());
        s.setIdea(r.idea());
        s.setBody(ScriptWriting.render(w));
        s.setSections(ScriptWriting.sections(w));
        s = scripts.save(s);
        audit.record("SCRIPT_WRITTEN", "script", s.getId(), Map.of("title", title));
        return toResponse(s);
    }

    @Transactional
    public ScriptResponse create(CreateRequest r) {
        checkProject(r.projectId());
        Script s = new Script(r.title().strip(), r.language(), type(r.videoType()).name());
        s.setProjectId(r.projectId());
        s.setIdea(r.idea());
        s.setBody(r.body());
        return toResponse(scripts.save(s));
    }

    /** Saves the creator's own edits in place (a hand edit is not a new version). */
    @Transactional
    public ScriptResponse update(UUID id, UpdateRequest r) {
        checkProject(r.projectId());
        Script s = find(id);
        s.setTitle(r.title().strip());
        s.setIdea(r.idea());
        if (!r.body().equals(s.getBody())) s.setSections(Map.of());   // structure no longer matches the text
        s.setBody(r.body());
        s.setLanguage(r.language());
        s.setVideoType(type(r.videoType()).name());
        s.setProjectId(r.projectId());
        return toResponse(scripts.save(s));
    }

    /** AI rewrite: kept as a new version, so the earlier one is still there. */
    @Transactional
    public ScriptResponse rewrite(UUID id, RewriteRequest r) {
        Script from = find(id);
        JsonNode reply = ai.askJson(r.writer().accountId(), r.writer().model(), ScriptWriting.rewriteSystem(),
                ScriptWriting.rewriteUser(r.instruction(), from.getBody(), from.getLanguage()));
        ScriptWriting.Written w = parse(reply);
        Script s = new Script(w.title().isBlank() ? from.getTitle() : w.title(), from.getLanguage(), from.getVideoType());
        s.setProjectId(from.getProjectId());
        s.setParentId(from.getId());
        s.setIdea(from.getIdea());
        s.setBody(ScriptWriting.render(w));
        s.setSections(ScriptWriting.sections(w));
        s.setMetadata(from.getMetadata());
        s.setVersionNo(from.getVersionNo() + 1);
        s = scripts.save(s);
        audit.record("SCRIPT_REWRITTEN", "script", s.getId(), Map.of("from", from.getId().toString()));
        return toResponse(s);
    }

    /** Titles, descriptions, captions and hashtags for the chosen platforms, saved on the script. */
    @Transactional
    public ScriptResponse metadata(UUID id, MetadataRequest r) {
        Script s = find(id);
        if (s.getBody().isBlank()) throw ApiException.badRequest("Write the script first");
        List<String> platforms = r.platforms().stream().map(String::toUpperCase).distinct().toList();
        for (String p : platforms) if (!PLATFORMS.contains(p)) throw ApiException.badRequest("Unknown platform: " + p);
        JsonNode reply = ai.askJson(r.writer().accountId(), r.writer().model(), ScriptWriting.metadataSystem(),
                ScriptWriting.metadataUser(s.getTitle(), s.getBody(), platforms, s.getLanguage()));
        Map<String, Object> parsed;
        try {
            parsed = ScriptWriting.parseMetadata(reply, platforms);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("The AI didn't return post text. Try again.");
        }
        Map<String, Object> merged = new java.util.LinkedHashMap<>(s.getMetadata());
        merged.putAll(parsed);
        s.setMetadata(merged);
        return toResponse(scripts.save(s));
    }

    @Transactional(readOnly = true)
    public List<ScriptResponse> list(UUID projectId) {
        return (projectId == null ? scripts.findTop100ByOrderByUpdatedAtDesc() : scripts.findTop100ByProjectIdOrderByUpdatedAtDesc(projectId))
                .stream().map(ScriptService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ScriptResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional
    public void delete(UUID id) {
        Script s = find(id);
        for (Script child : scripts.findByParentId(id)) child.setParentId(s.getParentId());
        scripts.delete(s);
        audit.record("SCRIPT_DELETED", "script", id, Map.of("title", s.getTitle()));
    }

    public Script find(UUID id) {
        return scripts.findById(id).orElseThrow(() -> ApiException.notFound("Script"));
    }

    private void checkProject(UUID projectId) {
        if (projectId != null && !projects.existsById(projectId)) throw ApiException.notFound("Project");
    }

    private static ScriptWriting.VideoType type(String t) {
        return ScriptWriting.VideoType.of(t);
    }

    private static ScriptWriting.Written parse(JsonNode reply) {
        try {
            return ScriptWriting.parseScript(reply);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("The AI reply had no script in it. Try again or use another model.");
        }
    }

    /** Spoken words per minute: about 150 in English, 110 in Tamil. */
    static double spokenMinutes(String body, Project.Language lang) {
        int words = words(spokenText(body));
        return Math.round(words / (lang == Project.Language.TA ? 110.0 : 150.0) * 10) / 10.0;
    }

    /** Leaves out [SCENE] markers and (Visual: …) notes, which aren't read aloud. */
    static String spokenText(String body) {
        return body.replaceAll("(?m)^\\[[^\\]]*]\\s*$", "").replaceAll("(?m)^\\(Visual:[^)]*\\)\\s*$", "");
    }

    static int words(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    static ScriptResponse toResponse(Script s) {
        return new ScriptResponse(s.getId(), s.getProjectId(), s.getParentId(), s.getTitle(), s.getLanguage(), s.getVideoType(),
                s.getIdea(), s.getBody(), s.getSections(), s.getMetadata(), s.getVersionNo(), words(spokenText(s.getBody())),
                spokenMinutes(s.getBody(), s.getLanguage()), s.getCreatedAt(), s.getUpdatedAt());
    }
}
