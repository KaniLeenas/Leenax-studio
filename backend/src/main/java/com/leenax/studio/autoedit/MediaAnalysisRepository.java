package com.leenax.studio.autoedit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MediaAnalysisRepository extends JpaRepository<MediaAnalysis, UUID> {
}
