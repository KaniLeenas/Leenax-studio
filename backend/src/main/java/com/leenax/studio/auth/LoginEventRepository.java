package com.leenax.studio.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LoginEventRepository extends JpaRepository<LoginEvent, UUID> {

    Page<LoginEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<LoginEvent> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
