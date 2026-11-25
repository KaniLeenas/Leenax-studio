package com.leenax.studio.ai.credits;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UsageLogRepository extends JpaRepository<UsageLog, UUID> {

    Page<UsageLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<UsageLog> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    List<UsageLog> findByCreatedAtAfter(Instant since);
}
