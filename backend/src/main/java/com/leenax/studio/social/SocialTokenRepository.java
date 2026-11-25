package com.leenax.studio.social;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SocialTokenRepository extends JpaRepository<SocialToken, UUID> {
}
