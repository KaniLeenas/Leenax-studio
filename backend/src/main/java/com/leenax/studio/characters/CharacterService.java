package com.leenax.studio.characters;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.characters.CharacterDtos.CharacterRequest;
import com.leenax.studio.characters.CharacterDtos.CharacterResponse;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.projects.ProjectRepository;
import com.leenax.studio.voice.VoiceProfile;
import com.leenax.studio.voice.VoiceProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reusable characters with reference images and an optional voice. */
@Service
public class CharacterService {

    private final StoryCharacterRepository characters;
    private final ReferenceImageRepository references;
    private final VoiceProfileRepository voices;
    private final ProjectRepository projects;
    private final MediaService media;
    private final AuditService audit;

    public CharacterService(StoryCharacterRepository characters, ReferenceImageRepository references, VoiceProfileRepository voices,
                            ProjectRepository projects, MediaService media, AuditService audit) {
        this.characters = characters;
        this.references = references;
        this.voices = voices;
        this.projects = projects;
        this.media = media;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CharacterResponse> list(UUID projectId) {
        return responses(projectId == null ? characters.findAllByOrderByNameAsc() : characters.findForProject(projectId));
    }

    @Transactional(readOnly = true)
    public CharacterResponse get(UUID id) {
        return responses(List.of(find(id))).get(0);
    }

    @Transactional
    public CharacterResponse create(CharacterRequest r) {
        StoryCharacter c = new StoryCharacter(r.name().strip());
        apply(c, r);
        c = characters.saveAndFlush(c);
        replaceReferences(c.getId(), r.referenceMediaIds());
        audit.record("CHARACTER_CREATED", "character", c.getId(), Map.of("name", c.getName()));
        return get(c.getId());
    }

    @Transactional
    public CharacterResponse update(UUID id, CharacterRequest r) {
        StoryCharacter c = find(id);
        c.setName(r.name().strip());
        apply(c, r);
        characters.save(c);
        replaceReferences(id, r.referenceMediaIds());
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        StoryCharacter c = find(id);
        characters.delete(c);   // its reference rows go with it (ON DELETE CASCADE); the images stay in the library
        audit.record("CHARACTER_DELETED", "character", id, Map.of("name", c.getName()));
    }

    public StoryCharacter find(UUID id) {
        return characters.findById(id).orElseThrow(() -> ApiException.notFound("Character"));
    }

    @Transactional(readOnly = true)
    public List<StoryCharacter> findAll(java.util.Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : characters.findAllById(ids);
    }

    /** Reference image ids per character, in order. */
    @Transactional(readOnly = true)
    public Map<UUID, List<UUID>> referenceIds(java.util.Collection<UUID> characterIds) {
        Map<UUID, List<UUID>> out = new LinkedHashMap<>();
        if (characterIds.isEmpty()) return out;
        for (ReferenceImage ref : references.findByCharacterIdInOrderByPositionAsc(characterIds)) {
            out.computeIfAbsent(ref.getCharacterId(), k -> new ArrayList<>()).add(ref.getMediaId());
        }
        return out;
    }

    private void apply(StoryCharacter c, CharacterRequest r) {
        if (r.projectId() != null && !projects.existsById(r.projectId())) throw ApiException.notFound("Project");
        if (r.voiceProfileId() != null && !voices.existsById(r.voiceProfileId())) throw ApiException.notFound("Voice profile");
        c.setProjectId(r.projectId());
        c.setDescription(blank(r.description()));
        c.setAppearance(blank(r.appearance()));
        c.setClothing(blank(r.clothing()));
        c.setVoiceProfileId(r.voiceProfileId());
    }

    private void replaceReferences(UUID characterId, List<UUID> mediaIds) {
        List<UUID> ids = mediaIds == null ? List.of() : mediaIds.stream().distinct().toList();
        if (!ids.isEmpty()) {
            Map<UUID, MediaAsset> found = media.findAssets(ids).stream().collect(Collectors.toMap(MediaAsset::getId, Function.identity()));
            for (UUID id : ids) {
                MediaAsset a = found.get(id);
                if (a == null) throw ApiException.notFound("Reference image");
                if (a.getKind() != MediaKind.IMAGE) throw ApiException.badRequest("Character references must be images");
            }
        }
        references.deleteAll(references.findByCharacterIdOrderByPositionAsc(characterId));
        references.flush();
        for (int i = 0; i < ids.size(); i++) references.save(ReferenceImage.forCharacter(characterId, ids.get(i), i));
    }

    private List<CharacterResponse> responses(List<StoryCharacter> list) {
        if (list.isEmpty()) return List.of();
        Map<UUID, List<UUID>> refs = referenceIds(list.stream().map(StoryCharacter::getId).toList());
        Set<UUID> mediaIds = new HashSet<>();
        refs.values().forEach(mediaIds::addAll);
        Map<UUID, MediaResponse> byId = media.findAssets(mediaIds).stream()
                .collect(Collectors.toMap(MediaAsset::getId, media::toResponse, (a, b) -> a));
        Set<UUID> voiceIds = list.stream().map(StoryCharacter::getVoiceProfileId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> voiceNames = voiceIds.isEmpty() ? Map.of()
                : voices.findAllById(voiceIds).stream().collect(Collectors.toMap(VoiceProfile::getId, VoiceProfile::getName));
        return list.stream().map(c -> new CharacterResponse(c.getId(), c.getProjectId(), c.getName(), c.getDescription(),
                c.getAppearance(), c.getClothing(), c.getVoiceProfileId(), voiceNames.get(c.getVoiceProfileId()),
                refs.getOrDefault(c.getId(), List.of()).stream().map(byId::get).filter(java.util.Objects::nonNull).toList(),
                c.getCreatedAt(), c.getUpdatedAt())).toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
