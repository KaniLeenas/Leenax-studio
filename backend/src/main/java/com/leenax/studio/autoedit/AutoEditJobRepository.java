package com.leenax.studio.autoedit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AutoEditJobRepository extends JpaRepository<AutoEditJob, UUID> {

    List<AutoEditJob> findTop30ByOrderByCreatedAtDesc();

    long countByStatusIn(List<AutoEditJob.Status> statuses);
}
