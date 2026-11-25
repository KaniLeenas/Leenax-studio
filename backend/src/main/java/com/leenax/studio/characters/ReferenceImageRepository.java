package com.leenax.studio.characters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReferenceImageRepository extends JpaRepository<ReferenceImage, UUID> {

    List<ReferenceImage> findByCharacterIdInOrderByPositionAsc(Collection<UUID> characterIds);

    List<ReferenceImage> findBySceneIdInOrderByPositionAsc(Collection<UUID> sceneIds);

    List<ReferenceImage> findByCharacterIdOrderByPositionAsc(UUID characterId);

    List<ReferenceImage> findBySceneIdOrderByPositionAsc(UUID sceneId);
}
