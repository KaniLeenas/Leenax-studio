package com.leenax.studio.voice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PronunciationRuleRepository extends JpaRepository<PronunciationRule, UUID> {

    List<PronunciationRule> findAllByOrderByTermAsc();
}
