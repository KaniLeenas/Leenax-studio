package com.leenax.studio.ai.generation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    Page<GenerationJob> findAllByOrderByCreatedAtDescVariationIndexAsc(Pageable pageable);

    Page<GenerationJob> findByStatusInOrderByCreatedAtDescVariationIndexAsc(Collection<GenerationJob.Status> statuses, Pageable pageable);

    List<GenerationJob> findByIdempotencyKeyOrderByVariationIndexAsc(String idempotencyKey);

    long countByStatusIn(Collection<GenerationJob.Status> statuses);
}
