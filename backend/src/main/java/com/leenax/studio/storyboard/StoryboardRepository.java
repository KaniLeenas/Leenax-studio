package com.leenax.studio.storyboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StoryboardRepository extends JpaRepository<Storyboard, UUID> {

    List<Storyboard> findTop100ByOrderByUpdatedAtDesc();

    List<Storyboard> findTop100ByProjectIdOrderByUpdatedAtDesc(UUID projectId);
}
