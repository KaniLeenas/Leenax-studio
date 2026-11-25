package com.leenax.studio.characters;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface StoryCharacterRepository extends JpaRepository<StoryCharacter, UUID> {

    List<StoryCharacter> findAllByOrderByNameAsc();

    /** The project's own characters plus the ones shared by every project. */
    @Query("select c from StoryCharacter c where c.projectId = :projectId or c.projectId is null order by c.name")
    List<StoryCharacter> findForProject(UUID projectId);
}
