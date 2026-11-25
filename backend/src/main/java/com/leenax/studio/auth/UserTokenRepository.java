package com.leenax.studio.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserTokenRepository extends JpaRepository<UserToken, UUID> {

    Optional<UserToken> findByTokenHash(byte[] tokenHash);

    /** Issuing a new link retires the person's older unused links of any kind. */
    @Modifying
    @Query("update UserToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int retireAll(UUID userId, Instant now);
}
