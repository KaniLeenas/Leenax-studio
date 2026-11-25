package com.leenax.studio.editor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RenderJobRepository extends JpaRepository<RenderJob, UUID> {

    List<RenderJob> findTop20ByTimelineIdOrderByCreatedAtDesc(UUID timelineId);

    List<RenderJob> findTop30ByOrderByCreatedAtDesc();

    long countByStatusIn(List<RenderJob.Status> statuses);
}
