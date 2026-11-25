package com.leenax.studio.storyboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SceneRepository extends JpaRepository<Scene, UUID> {

    List<Scene> findByStoryboardIdOrderByPositionAsc(UUID storyboardId);

    List<Scene> findByStoryboardIdInOrderByPositionAsc(Collection<UUID> storyboardIds);

    long countByStoryboardId(UUID storyboardId);
}
