package com.leenax.studio.ai.accounts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApiCredentialRepository extends JpaRepository<ApiCredential, UUID> {
}
