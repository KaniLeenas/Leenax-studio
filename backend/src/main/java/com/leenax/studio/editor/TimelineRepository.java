package com.leenax.studio.editor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TimelineRepository extends JpaRepository<Timeline, UUID> {

    Page<Timeline> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    Page<Timeline> findByProjectIdOrderByUpdatedAtDesc(UUID projectId, Pageable pageable);
}
