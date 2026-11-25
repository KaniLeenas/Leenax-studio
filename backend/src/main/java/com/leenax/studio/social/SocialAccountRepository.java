package com.leenax.studio.social;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, UUID> {

    Optional<SocialAccount> findByPlatformAndExternalId(SocialPlatform platform, String externalId);

    List<SocialAccount> findAllByOrderByPlatformAscDisplayNameAsc();
}
