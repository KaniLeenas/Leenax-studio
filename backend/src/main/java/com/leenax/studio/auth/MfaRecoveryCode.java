package com.leenax.studio.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A one-time recovery code, stored as its SHA-256 hash. */
@Entity
@Table(name = "mfa_recovery_codes")
public class MfaRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MfaRecoveryCode() {
    }

    public MfaRecoveryCode(UUID userId, String codeHash) {
        this.userId = userId;
        this.codeHash = codeHash;
    }

    public String getCodeHash() { return codeHash; }
    public Instant getUsedAt() { return usedAt; }
    public void markUsed() { this.usedAt = Instant.now(); }
}
