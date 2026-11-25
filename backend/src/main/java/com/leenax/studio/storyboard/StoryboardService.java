package com.leenax.studio.storyboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.generation.GenerationDtos.VideoGenerationRequest;
import com.leenax.studio.ai.generation.GenerationJob;
import com.leenax.studio.ai.generation.GenerationJobRepository;
import com.leenax.studio.ai.generation.GenerationService;
import com.leenax.studio.ai.provider.ProviderCode;
import com.leenax.studio.ai.provider.VideoRequest;
import com.leenax.studio.ai.text.TextAi;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.autoedit.AutoEditProcessor;
import com.leenax.studio.autoedit.CaptionChunker;
import com.leenax.studio.autoedit.TimelineBuilder;
import com.leenax.studio.autoedit.TimelineBuilder.TextLook;
import com.leenax.studio.autoedit.TimelineBuilder.TrackKind;
import com.leenax.studio.characters.CharacterService;
import com.leenax.studio.characters.ReferenceImage;
import com.leenax.studio.characters.ReferenceImageRepository;
import com.leenax.studio.characters.StoryCharacter;
import com.leenax.studio.characters.StoryCharacterRepository;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.editor.EditorDtos.CreateTimelineRequest;
import com.leenax.studio.editor.TimelineService;
import com.leenax.studio.images.ImageDtos.GenerateRequest;
import com.leenax.studio.images.ImageService;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.projects.ProjectRepository;
import com.leenax.studio.scripts.Script;
import com.leenax.studio.scripts.ScriptService;
import com.leenax.studio.storyboard.StoryboardDtos.*;
import com.leenax.studio.voice.VoiceDtos.DialogueRequest;
import com.leenax.studio.voice.VoiceDtos.Speaker;
import com.leenax.studio.voice.VoiceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Storyboards: script → scenes, scene editing, a still and a video per scene
 * (through the image and video generators, with character references), voice,
 * and export to an editable timeline. Every file lands in the project.
 */
@Service
public class StoryboardService {

    private final StoryboardRepository boards;
    private final SceneRepository scenes;
    private final ReferenceImageRepository references;
    private final StoryCharacterRepository characterRepo;
    private final CharacterService characters;
    private final ScriptService scripts;
    private final ProjectRepository projects;
    private final TextAi ai;
    private final ImageService images;
    private final GenerationService generation;
    private final GenerationJobRepository jobs;
    private final AccountService accounts;
    private final VoiceService voice;
    private final MediaService media;
    private final TimelineService timelines;
    private final AuditService audit;

    public StoryboardService(StoryboardRepository boards, SceneRepository scenes, ReferenceImageRepository references,
                             StoryCharacterRepository characterRepo, CharacterService characters, ScriptService scripts,
                             ProjectRepository projects, TextAi ai, ImageService images, GenerationService generation,
                             GenerationJobRepository jobs, AccountService accounts, VoiceService voice, MediaService media,
                             TimelineService timelines, AuditService audit) {
        this.boards = boards;
        this.scenes = scenes;
        this.references = references;
        this.characterRepo = characterRepo;
        this.characters = characters;
        this.scripts = scripts;
        this.projects = projects;
        this.ai = ai;
        this.images = images;
        this.generation = generation;
        this.jobs = jobs;
        this.accounts = accounts;
        this.voice = voice;
        this.media = media;
        this.timelines = timelines;
        this.audit = audit;
    }

    // ---- storyboards ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<StoryboardSummary> list(UUID projectId) {
        List<Storyboard> list = projectId == null ? boards.findTop100ByOrderByUpdatedAtDesc() : boards.findTop100ByProjectIdOrderByUpdatedAtDesc(projectId);
        if (list.isEmpty()) return List.of();
        Map<UUID, List<Scene>> byBoard = scenes.findByStoryboardIdInOrderByPositionAsc(list.stream().map(Storyboard::getId).toList())
                .stream().collect(Collectors.groupingBy(Scene::getStoryboardId));
        Set<UUID> covers = new HashSet<>();
        for (List<Scene> ss : byBoard.values()) ss.stream().map(Scene::getImageMediaId).filter(Objects::nonNull).findFirst().ifPresent(covers::add);
        Map<UUID, MediaResponse> coverMedia = mediaById(covers);
        return list.stream().map(b -> {
            List<Scene> ss = byBoard.getOrDefault(b.getId(), List.of());
            UUID cover = ss.stream().map(Scene::getImageMediaId).filter(Objects::nonNull).findFirst().orElse(null);
            return new StoryboardSummary(b.getId(), b.getProjectId(), b.getScriptId(), b.getTitle(), b.getAspectRatio(), b.getLanguage(),
                    ss.size(), (int) ss.stream().filter(s -> s.getImageMediaId() != null).count(),
                    (int) ss.stream().filter(s -> s.getVideoMediaId() != null).count(),
                    ss.stream().mapToDouble(Scene::getDurationSeconds).sum(), cover == null ? null : coverMedia.get(cover),
                    b.getTimelineId(), b.getUpdatedAt());
        }).toList();
    }

    /** The full storyboard. Also links finished scene videos (generation runs in the background). */
    @Transactional
    public StoryboardResponse get(UUID id) {
        return response(find(id));
    }

    @Transactional
    public StoryboardResponse create(CreateRequest r) {
        checkProject(r.projectId());
        Storyboard b = new Storyboard(r.title().strip(), r.aspectRatio(), r.language());
        b.setProjectId(r.projectId());
        b.setStyle(blank(r.style()));
        b = boards.saveAndFlush(b);
        audit.record("STORYBOARD_CREATED", "storyboard", b.getId(), Map.of("title", b.getTitle()));
        return response(b);
    }

    /** Splits a script into shots with the AI, linking the characters it mentions. */
    @Transactional
    public StoryboardResponse fromScript(FromScriptRequest r) {
        Script script = scripts.find(r.scriptId());
        if (script.getBody().isBlank()) throw ApiException.badRequest("The script is empty");
        List<StoryCharacter> cast = script.getProjectId() == null ? characterRepo.findAllByOrderByNameAsc()
                : characterRepo.findForProject(script.getProjectId());
        JsonNode reply = ai.askJson(r.writer().accountId(), r.writer().model(), StoryboardPlanning.scenesSystem(),
                StoryboardPlanning.scenesUser(script.getBody(), cast, r.aspectRatio(), script.getLanguage(), r.style()));
        StoryboardPlanning.Plan plan;
        try {
            plan = StoryboardPlanning.parse(reply);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("The AI didn't return any scenes. Try again or use another model.");
        }
        String title = r.title() != null && !r.title().isBlank() ? r.title().strip() : script.getTitle();
        Storyboard b = new Storyboard(title, r.aspectRatio(), script.getLanguage());
        b.setProjectId(script.getProjectId());
        b.setScriptId(script.getId());
        b.setStyle(r.style() != null && !r.style().isBlank() ? r.style().strip() : blank(plan.style()));
        b = boards.saveAndFlush(b);
        int pos = 0;
        for (StoryboardPlanning.Draft d : plan.scenes()) {
            Scene s = new Scene(b.getId(), pos++);
            s.setTitle(d.title());
            s.setDescription(d.description());
            s.setNarration(d.narration());
            s.setDialogue(d.dialogue());
            s.setDurationSeconds(d.durationSeconds());
            s.setCameraAngle(d.cameraAngle());
            s.setCameraMovement(d.cameraMovement());
            s.setLighting(blank(d.lighting()));
            s.setComposition(blank(d.composition()));
            s.setSoundNotes(blank(d.sound()));
            s.setCharacterIds(StoryboardPlanning.matchCharacters(d.characterNames(), cast));
            scenes.save(s);
        }
        audit.record("STORYBOARD_FROM_SCRIPT", "storyboard", b.getId(), Map.of("scenes", plan.scenes().size()));
        return response(b);
    }

    @Transactional
    public StoryboardResponse update(UUID id, UpdateRequest r) {
        checkProject(r.projectId());
        Storyboard b = find(id);
        b.setTitle(r.title().strip());
        b.setAspectRatio(r.aspectRatio());
        b.setLanguage(r.language());
        b.setStyle(blank(r.style()));
        b.setProjectId(r.projectId());
        return response(boards.save(b));
    }

    @Transactional
    public void delete(UUID id) {
        Storyboard b = find(id);
        boards.delete(b);   // scenes and their reference rows go with it; images and videos stay in the library
        audit.record("STORYBOARD_DELETED", "storyboard", id, Map.of("title", b.getTitle()));
    }

    // ---- scenes ---------------------------------------------------------------------------------------

    @Transactional
    public StoryboardResponse addScene(UUID boardId, SceneRequest r, Integer at) {
        Storyboard b = find(boardId);
        List<Scene> list = scenes.findByStoryboardIdOrderByPositionAsc(boardId);
        if (list.size() >= StoryboardPlanning.MAX_SCENES) throw ApiException.badRequest("A storyboard can have up to " + StoryboardPlanning.MAX_SCENES + " scenes");
        int index = at == null ? list.size() : Math.max(0, Math.min(list.size(), at));
        Scene s = new Scene(boardId, index);
        apply(s, r == null ? emptyRequest() : r, b);
        list.add(index, s);
        renumber(list);
        scenes.saveAll(list);
        scenes.flush();
        if (r != null) replaceSceneReferences(s.getId(), r.referenceMediaIds());
        touch(b);
        return response(b);
    }

    @Transactional
    public StoryboardResponse updateScene(UUID sceneId, SceneRequest r) {
        Scene s = scene(sceneId);
        Storyboard b = find(s.getStoryboardId());
        apply(s, r, b);
        scenes.save(s);
        if (r.referenceMediaIds() != null) replaceSceneReferences(sceneId, r.referenceMediaIds());
        touch(b);
        return response(b);
    }

    @Transactional
    public StoryboardResponse duplicateScene(UUID sceneId) {
        Scene s = scene(sceneId);
        Storyboard b = find(s.getStoryboardId());
        List<Scene> list = scenes.findByStoryboardIdOrderByPositionAsc(b.getId());
        if (list.size() >= StoryboardPlanning.MAX_SCENES) throw ApiException.badRequest("A storyboard can have up to " + StoryboardPlanning.MAX_SCENES + " scenes");
        Scene copy = s.copy(s.getPosition() + 1);
        list.add(s.getPosition() + 1, copy);
        renumber(list);
        scenes.saveAll(list);
        scenes.flush();
        List<UUID> refs = references.findBySceneIdOrderByPositionAsc(sceneId).stream().map(ReferenceImage::getMediaId).toList();
        replaceSceneReferences(copy.getId(), refs);
        touch(b);
        return response(b);
    }

    @Transactional
    public StoryboardResponse deleteScene(UUID sceneId) {
        Scene s = scene(sceneId);
        Storyboard b = find(s.getStoryboardId());
        scenes.delete(s);
        scenes.flush();
        List<Scene> rest = scenes.findByStoryboardIdOrderByPositionAsc(b.getId());
        renumber(rest);
        scenes.saveAll(rest);
        touch(b);
        return response(b);
    }

    @Transactional
    public StoryboardResponse reorder(UUID boardId, ReorderRequest r) {
        Storyboard b = find(boardId);
        List<Scene> list = scenes.findByStoryboardIdOrderByPositionAsc(boardId);
        Map<UUID, Scene> byId = list.stream().collect(Collectors.toMap(Scene::getId, Function.identity()));
        if (r.sceneIds().size() != list.size() || !byId.keySet().equals(new HashSet<>(r.sceneIds()))) {
            throw ApiException.badRequest("Send every scene of the storyboard exactly once");
        }
        List<Scene> ordered = r.sceneIds().stream().map(byId::get).collect(Collectors.toCollection(ArrayList::new));
        renumber(ordered);
        scenes.saveAll(ordered);
        touch(b);
        return response(b);
    }

    // ---- generation -------------------------------------------------------------------------------------

    /** Makes (or remakes) the scene's still with the image generator, sending the characters' reference images. */
    @Transactional
    public StoryboardResponse generateImage(UUID sceneId, SceneImageRequest r) {
        Scene s = scene(sceneId);
        Storyboard b = find(s.getStoryboardId());
        List<StoryCharacter> cast = cast(s);
        String prompt = StoryboardPlanning.imagePrompt(s, cast, b.getStyle(), b.getAspectRatio());
        List<UUID> refs = imageReferences(s, cast);
        boolean openai = accounts.get(r.accountId()).getProvider() == ProviderCode.OPENAI;
        var made = images.generate(new GenerateRequest(r.accountId(), r.model(), prompt.length() > 4000 ? prompt.substring(0, 4000) : prompt,
                openai ? AutoEditProcessor.openAiSize(b.getAspectRatio()) : null, openai ? null : AutoEditProcessor.aspect(b.getAspectRatio()),
                openai ? null : "1K", 1, null, false, refs.isEmpty() ? null : refs, b.getProjectId()));
        if (made.images().isEmpty()) throw ApiException.badRequest("The provider returned no image");
        s.setImageMediaId(made.images().get(0).id());
        scenes.save(s);
        touch(b);
        return response(b);
    }

    /**
     * Starts a video for the scene in the background: image-to-video from the
     * scene's still, or text-to-video from its description. The finished file
     * is linked to the scene when the storyboard is next loaded.
     */
    @Transactional
    public StoryboardResponse generateVideo(UUID sceneId, SceneVideoRequest r) {
        Scene s = scene(sceneId);
        Storyboard b = find(s.getStoryboardId());
        if (r.mode() == VideoRequest.Mode.IMAGE_TO_VIDEO && s.getImageMediaId() == null) {
            throw ApiException.badRequest("Make or choose the scene's image first, or use text-to-video");
        }
        String prompt = StoryboardPlanning.videoPrompt(s, cast(s), b.getStyle());
        int seconds = r.durationSeconds() != null ? r.durationSeconds() : (int) Math.round(s.getDurationSeconds());
        var created = generation.create(new VideoGenerationRequest(r.accountId(), r.modelId(), r.mode(), prompt, null, seconds,
                AutoEditProcessor.aspect(b.getAspectRatio()), r.resolution(), r.audio(),
                r.mode() == VideoRequest.Mode.IMAGE_TO_VIDEO ? s.getImageMediaId() : null, b.getProjectId(), 1), null);
        s.setVideoJobId(created.jobs().get(0).id());
        scenes.save(s);
        touch(b);
        return response(b);
    }

    /** One voice-over for every scene's narration, in order; each scene keeps its part. */
    @Transactional
    public StoryboardResponse narrate(UUID boardId, NarrateRequest r) {
        Storyboard b = find(boardId);
        List<Scene> spoken = scenes.findByStoryboardIdOrderByPositionAsc(boardId).stream().filter(s -> !s.getNarration().isBlank()).toList();
        if (spoken.isEmpty()) throw ApiException.badRequest("No scene has narration to speak");
        var result = voice.narrateScenes(r.voice(), b.getLanguage().name(), r.style(),
                spoken.stream().map(Scene::getNarration).toList(), 0.35, b.getTitle() + " narration", b.getProjectId());
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int i = 0; i < spoken.size() && i < result.parts().size(); i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("sceneId", spoken.get(i).getId().toString());
            p.put("start", result.parts().get(i)[0]);
            p.put("length", result.parts().get(i)[1]);
            parts.add(p);
        }
        b.setNarrationMediaId(result.media().getId());
        b.setNarrationParts(parts);
        boards.save(b);
        return response(b);
    }

    /** Speaks the scene's dialogue in each character's own voice (set in Character studio). */
    @Transactional
    public StoryboardResponse voiceDialogue(UUID sceneId) {
        Scene s = scene(sceneId);
        Storyboard b = find(s.getStoryboardId());
        if (s.getDialogue().isEmpty()) throw ApiException.badRequest("This scene has no dialogue");
        List<StoryCharacter> pool = b.getProjectId() == null ? characterRepo.findAllByOrderByNameAsc() : characterRepo.findForProject(b.getProjectId());
        Map<String, StoryCharacter> byName = new HashMap<>();
        for (StoryCharacter c : pool) byName.putIfAbsent(c.getName().toLowerCase(Locale.ROOT).strip(), c);
        List<Speaker> speakers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> line : s.getDialogue()) {
            String name = String.valueOf(line.get("character")).strip();
            if (!seen.add(name.toLowerCase(Locale.ROOT))) continue;
            StoryCharacter c = byName.get(name.toLowerCase(Locale.ROOT));
            if (c == null) throw ApiException.badRequest("“" + name + "” isn't in Character studio. Add them, then give them a voice.");
            if (c.getVoiceProfileId() == null) throw ApiException.badRequest("Give “" + c.getName() + "” a voice in Character studio first");
            speakers.add(new Speaker(c.getName().length() > 40 ? c.getName().substring(0, 40) : c.getName(), c.getVoiceProfileId()));
        }
        if (speakers.size() > 6) throw ApiException.badRequest("A scene's dialogue can have up to 6 speakers");
        var result = voice.dialogue(new DialogueRequest(StoryboardPlanning.dialogueScript(s.getDialogue()), speakers,
                b.getLanguage().name(), 0.3, shorten(b.getTitle() + " – " + (s.getTitle().isBlank() ? "scene " + (s.getPosition() + 1) : s.getTitle()), 160),
                b.getProjectId()));
        s.setDialogueMediaId(result.media().id());
        scenes.save(s);
        return response(b);
    }

    // ---- export ---------------------------------------------------------------------------------------

    /**
     * Builds an editable timeline: each scene's video (or its still with gentle
     * motion, or a title card), the narration and dialogue on their own tracks,
     * optional captions from the narration, and optional music under it all.
     */
    @Transactional
    public ExportResponse export(UUID boardId, ExportRequest r) {
        Storyboard b = find(boardId);
        List<Scene> list = scenes.findByStoryboardIdOrderByPositionAsc(boardId);
        if (list.isEmpty()) throw ApiException.badRequest("Add scenes first");
        syncVideos(list);
        Set<UUID> ids = new HashSet<>();
        for (Scene s : list) {
            if (s.getVideoMediaId() != null) ids.add(s.getVideoMediaId());
            if (s.getDialogueMediaId() != null) ids.add(s.getDialogueMediaId());
        }
        Map<UUID, MediaAsset> assets = media.findAssets(ids).stream().collect(Collectors.toMap(MediaAsset::getId, Function.identity()));
        Map<String, double[]> narration = new HashMap<>();
        if (b.getNarrationMediaId() != null) {
            for (Map<String, Object> p : b.getNarrationParts()) {
                narration.put(String.valueOf(p.get("sceneId")), new double[]{num(p.get("start")), num(p.get("length"))});
            }
        }
        MediaAsset music = r.musicMediaId() == null ? null : media.findAsset(r.musicMediaId());
        if (music != null && music.getKind() != MediaKind.AUDIO) throw ApiException.badRequest("The music must be an audio file");

        Plan plan = plan(list, assets, narration, b.getAspectRatio(), r.captions(), b.getNarrationMediaId(), music);
        String name = r.name() != null && !r.name().isBlank() ? r.name().strip() : b.getTitle();
        UUID timelineId = timelines.create(new CreateTimelineRequest(shorten(name, 160), plan.document(), b.getProjectId())).id();
        b.setTimelineId(timelineId);
        boards.save(b);
        audit.record("STORYBOARD_EXPORTED", "storyboard", boardId, Map.of("timeline", timelineId.toString()));
        return new ExportResponse(timelineId, plan.total(), plan.notes());
    }

    record Plan(Map<String, Object> document, double total, List<String> notes) {
    }

    /** The timeline layout, kept separate from saving so it can be tested. */
    static Plan plan(List<Scene> list, Map<UUID, MediaAsset> assets, Map<String, double[]> narration, TimelineBuilder.Format format,
                     boolean captions, UUID narrationMediaId, MediaAsset music) {
        var tb = new TimelineBuilder(format, 30);
        var titles = tb.track(TrackKind.TEXT, "Titles", 1);
        var captionTrack = captions ? tb.track(TrackKind.TEXT, "Captions", 1) : null;
        var video = tb.track(TrackKind.VIDEO, "Scenes", 1);
        var voiceTrack = tb.track(TrackKind.AUDIO, "Narration", 1);
        var dialogueTrack = tb.track(TrackKind.AUDIO, "Dialogue", 1);
        var musicTrack = tb.track(TrackKind.AUDIO, "Music", 1);
        boolean portrait = format == TimelineBuilder.Format.PORTRAIT;
        String[] motions = {"zoom-in", "pan-left", "zoom-out", "pan-right"};
        List<String> notes = new ArrayList<>();
        int stills = 0;
        int cards = 0;
        double t = 0;
        for (Scene s : list) {
            double[] part = narration.get(s.getId().toString());
            MediaAsset dialogue = s.getDialogueMediaId() == null ? null : assets.get(s.getDialogueMediaId());
            double spoken = Math.max(part == null ? 0 : part[1], dialogue == null ? 0 : seconds(dialogue));
            double length = StoryboardPlanning.length(s.getDurationSeconds(), spoken);
            MediaAsset clip = s.getVideoMediaId() == null ? null : assets.get(s.getVideoMediaId());
            double shown = 0;
            if (clip != null) {
                double len = seconds(clip) > 0 ? Math.min(length, seconds(clip)) : length;
                var c = tb.media(video, clip.getId(), "VIDEO", t, len, 0);
                c.put("volume", 0);   // generated clips' own sound stays off under narration and dialogue
                shown = len;
            }
            if (shown < length - 0.05 && s.getImageMediaId() != null) {
                var c = tb.media(video, s.getImageMediaId(), "IMAGE", t + shown, length - shown, 0);
                c.put("motion", motions[stills++ % motions.length]);
                shown = length;
            }
            if (shown < length - 0.05) {
                String card = !s.getTitle().isBlank() ? s.getTitle() : shorten(s.getDescription(), 60);
                tb.text(titles, card.isBlank() ? "Scene " + (s.getPosition() + 1) : card, t + shown, length - shown, TextLook.TITLE);
                cards++;
            }
            if (part != null && narrationMediaId != null) {
                tb.media(voiceTrack, narrationMediaId, "AUDIO", t, part[1], part[0]);
                if (captionTrack != null) tb.captions(captionTrack, CaptionChunker.fromText(s.getNarration(), t, part[1], portrait ? 24 : 42), portrait);
            }
            if (dialogue != null) tb.media(dialogueTrack, dialogue.getId(), "AUDIO", t, seconds(dialogue), 0);
            t += length;
        }
        if (music != null && music.getDurationMs() != null) {
            tb.music(musicTrack, music.getId(), music.getDurationMs() / 1000.0, t, narrationMediaId != null ? 0.15 : 0.25);
        }
        if (cards > 0) notes.add(cards + " scene" + (cards == 1 ? " has" : "s have") + " no image or video yet, so a title card stands in.");
        if (narrationMediaId == null) notes.add("No narration was recorded; scenes use their planned length.");
        return new Plan(tb.build(), t, notes);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private void apply(Scene s, SceneRequest r, Storyboard b) {
        if (r.title() != null) s.setTitle(r.title().strip());
        if (r.description() != null) s.setDescription(r.description().strip());
        if (r.narration() != null) s.setNarration(r.narration().strip());
        if (r.dialogue() != null) {
            List<Map<String, Object>> d = new ArrayList<>();
            for (DialogueLine l : r.dialogue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("character", l.character().strip());
                m.put("line", l.line().strip());
                d.add(m);
            }
            s.setDialogue(d);
        }
        if (r.durationSeconds() != null) s.setDurationSeconds(r.durationSeconds());
        if (r.cameraAngle() != null) s.setCameraAngle(r.cameraAngle().isBlank() ? null : StoryboardPlanning.pick(r.cameraAngle(), StoryboardPlanning.ANGLES));
        if (r.cameraMovement() != null) s.setCameraMovement(r.cameraMovement().isBlank() ? null : StoryboardPlanning.pick(r.cameraMovement(), StoryboardPlanning.MOVES));
        if (r.lighting() != null) s.setLighting(blank(r.lighting()));
        if (r.composition() != null) s.setComposition(blank(r.composition()));
        if (r.soundNotes() != null) s.setSoundNotes(blank(r.soundNotes()));
        if (r.characterIds() != null) {
            List<UUID> ids = r.characterIds().stream().distinct().toList();
            if (characters.findAll(ids).size() != ids.size()) throw ApiException.notFound("Character");
            s.setCharacterIds(ids.stream().map(UUID::toString).toList());
        }
        if (r.imagePrompt() != null) s.setImagePrompt(blank(r.imagePrompt()));
        if (r.videoPrompt() != null) s.setVideoPrompt(blank(r.videoPrompt()));
        if (r.imageMediaId() != null) {
            MediaAsset a = media.findAsset(r.imageMediaId());
            if (a.getKind() != MediaKind.IMAGE) throw ApiException.badRequest("The scene image must be an image");
            s.setImageMediaId(a.getId());
        }
        if (r.videoMediaId() != null) {
            MediaAsset a = media.findAsset(r.videoMediaId());
            if (a.getKind() != MediaKind.VIDEO) throw ApiException.badRequest("The scene video must be a video");
            s.setVideoMediaId(a.getId());
            s.setVideoJobId(null);
        }
    }

    private void replaceSceneReferences(UUID sceneId, List<UUID> mediaIds) {
        if (mediaIds == null) return;
        List<UUID> ids = mediaIds.stream().distinct().toList();
        for (MediaAsset a : media.findAssets(ids)) {
            if (a.getKind() != MediaKind.IMAGE) throw ApiException.badRequest("Scene references must be images");
        }
        references.deleteAll(references.findBySceneIdOrderByPositionAsc(sceneId));
        references.flush();
        for (int i = 0; i < ids.size(); i++) references.save(ReferenceImage.forScene(sceneId, ids.get(i), i));
    }

    private List<StoryCharacter> cast(Scene s) {
        return characters.findAll(s.getCharacterIds().stream().map(UUID::fromString).toList());
    }

    /** Up to 4 references: the scene's own first, then the first image of each character in it. */
    List<UUID> imageReferences(Scene s, List<StoryCharacter> cast) {
        List<UUID> out = new ArrayList<>(references.findBySceneIdOrderByPositionAsc(s.getId()).stream().map(ReferenceImage::getMediaId).toList());
        Map<UUID, List<UUID>> byCharacter = characters.referenceIds(cast.stream().map(StoryCharacter::getId).toList());
        for (StoryCharacter c : cast) {
            List<UUID> refs = byCharacter.getOrDefault(c.getId(), List.of());
            if (!refs.isEmpty() && !out.contains(refs.get(0))) out.add(refs.get(0));
        }
        return out.size() > 4 ? out.subList(0, 4) : out;
    }

    /** Links scene videos whose generation has finished. */
    private void syncVideos(List<Scene> list) {
        Set<UUID> jobIds = list.stream().map(Scene::getVideoJobId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (jobIds.isEmpty()) return;
        Map<UUID, GenerationJob> byId = jobs.findAllById(jobIds).stream().collect(Collectors.toMap(GenerationJob::getId, Function.identity()));
        for (Scene s : list) {
            GenerationJob j = s.getVideoJobId() == null ? null : byId.get(s.getVideoJobId());
            if (j != null && j.getStatus() == GenerationJob.Status.COMPLETED && j.getOutputMediaId() != null
                    && !j.getOutputMediaId().equals(s.getVideoMediaId())) {
                s.setVideoMediaId(j.getOutputMediaId());
                scenes.save(s);
            }
        }
    }

    private StoryboardResponse response(Storyboard b) {
        List<Scene> list = scenes.findByStoryboardIdOrderByPositionAsc(b.getId());
        syncVideos(list);
        Set<UUID> jobIds = list.stream().map(Scene::getVideoJobId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, GenerationJob> jobById = jobIds.isEmpty() ? Map.of()
                : jobs.findAllById(jobIds).stream().collect(Collectors.toMap(GenerationJob::getId, Function.identity()));
        Map<UUID, List<UUID>> sceneRefs = new HashMap<>();
        if (!list.isEmpty()) {
            for (ReferenceImage r : references.findBySceneIdInOrderByPositionAsc(list.stream().map(Scene::getId).toList())) {
                sceneRefs.computeIfAbsent(r.getSceneId(), k -> new ArrayList<>()).add(r.getMediaId());
            }
        }
        Set<UUID> mediaIds = new HashSet<>();
        for (Scene s : list) {
            if (s.getImageMediaId() != null) mediaIds.add(s.getImageMediaId());
            if (s.getVideoMediaId() != null) mediaIds.add(s.getVideoMediaId());
            if (s.getDialogueMediaId() != null) mediaIds.add(s.getDialogueMediaId());
            mediaIds.addAll(sceneRefs.getOrDefault(s.getId(), List.of()));
        }
        if (b.getNarrationMediaId() != null) mediaIds.add(b.getNarrationMediaId());
        Map<UUID, MediaResponse> m = mediaById(mediaIds);
        Set<UUID> characterIds = new HashSet<>();
        list.forEach(s -> s.getCharacterIds().forEach(id -> characterIds.add(UUID.fromString(id))));
        Map<UUID, StoryCharacter> castById = characters.findAll(characterIds).stream().collect(Collectors.toMap(StoryCharacter::getId, Function.identity()));
        List<SceneResponse> out = new ArrayList<>();
        for (Scene s : list) {
            List<StoryCharacter> cast = s.getCharacterIds().stream().map(id -> castById.get(UUID.fromString(id))).filter(Objects::nonNull).toList();
            GenerationJob j = s.getVideoJobId() == null ? null : jobById.get(s.getVideoJobId());
            VideoJob vj = j == null ? null : new VideoJob(j.getId(), j.getStatus(), j.getProgress() == null ? null : j.getProgress().doubleValue(),
                    j.getErrorMessage(), j.getProvider().displayName(), j.getModelName());
            out.add(new SceneResponse(s.getId(), s.getPosition(), s.getTitle(), s.getDescription(), s.getNarration(), s.getDialogue(),
                    s.getDurationSeconds(), s.getCameraAngle(), s.getCameraMovement(), s.getLighting(), s.getComposition(), s.getSoundNotes(),
                    s.getCharacterIds().stream().map(UUID::fromString).toList(), s.getImagePrompt(), s.getVideoPrompt(),
                    StoryboardPlanning.imagePrompt(withoutOverride(s), cast, b.getStyle(), b.getAspectRatio()),
                    StoryboardPlanning.videoPrompt(withoutOverride(s), cast, b.getStyle()),
                    m.get(s.getImageMediaId()), m.get(s.getVideoMediaId()), m.get(s.getDialogueMediaId()), vj,
                    sceneRefs.getOrDefault(s.getId(), List.of()).stream().map(m::get).filter(Objects::nonNull).toList()));
        }
        return new StoryboardResponse(b.getId(), b.getProjectId(), b.getScriptId(), b.getTitle(), b.getAspectRatio(), b.getLanguage(),
                b.getStyle(), m.get(b.getNarrationMediaId()), b.getNarrationParts(), b.getTimelineId(),
                list.stream().mapToDouble(Scene::getDurationSeconds).sum(), out, b.getCreatedAt(), b.getUpdatedAt());
    }

    /** The prompt the studio would write, ignoring the user's own override (shown as a suggestion). */
    private static Scene withoutOverride(Scene s) {
        Scene c = s.copy(s.getPosition());
        c.setTitle(s.getTitle());
        c.setImagePrompt(null);
        c.setVideoPrompt(null);
        return c;
    }

    private Map<UUID, MediaResponse> mediaById(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return media.findAssets(ids).stream().collect(Collectors.toMap(MediaAsset::getId, media::toResponse, (a, c) -> a));
    }

    private Storyboard find(UUID id) {
        return boards.findById(id).orElseThrow(() -> ApiException.notFound("Storyboard"));
    }

    private Scene scene(UUID id) {
        return scenes.findById(id).orElseThrow(() -> ApiException.notFound("Scene"));
    }

    private void touch(Storyboard b) {
        b.touchNow();
        boards.save(b);
    }

    private void checkProject(UUID projectId) {
        if (projectId != null && !projects.existsById(projectId)) throw ApiException.notFound("Project");
    }

    private static void renumber(List<Scene> list) {
        for (int i = 0; i < list.size(); i++) list.get(i).setPosition(i);
    }

    private static SceneRequest emptyRequest() {
        return new SceneRequest("", "", "", null, 5.0, null, null, null, null, null, null, null, null, null, null, null);
    }

    static double seconds(MediaAsset a) {
        return a.getDurationMs() == null ? 0 : a.getDurationMs() / 1000.0;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String shorten(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max - 1).strip() + "…";
    }
}
