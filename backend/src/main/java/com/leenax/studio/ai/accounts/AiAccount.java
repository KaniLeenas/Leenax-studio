package com.leenax.studio.ai.accounts;

import com.leenax.studio.ai.provider.ProviderCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A named connection to one provider account, e.g. "Runway personal". */
@Entity
@Table(name = "ai_accounts")
public class AiAccount {

    public enum Status { ACTIVE, UNVERIFIED, INVALID, DISABLED }

    /** Assigned in code (not by the database) because it is bound into the key's encryption. */
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProviderCode provider;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.UNVERIFIED;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "key_hint", length = 8)
    private String keyHint;

    @Column(name = "low_credit_threshold", precision = 14, scale = 2)
    private BigDecimal lowCreditThreshold;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "balance_synced_at")
    private Instant balanceSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AiAccount() {
    }

    public AiAccount(ProviderCode provider, String name) {
        this.id = UUID.randomUUID();
        this.provider = provider;
        this.name = name;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean isUsable() {
        return status == Status.ACTIVE || status == Status.UNVERIFIED;
    }

    public void markVerified() {
        status = Status.ACTIVE;
        lastVerifiedAt = Instant.now();
        lastError = null;
    }

    public void markFailed(Status newStatus, String error) {
        status = newStatus;
        lastError = error == null ? null : (error.length() > 300 ? error.substring(0, 299) + "…" : error);
    }

    public UUID getId() { return id; }
    public ProviderCode getProvider() { return provider; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean value) { this.isDefault = value; }
    public String getKeyHint() { return keyHint; }
    public void setKeyHint(String keyHint) { this.keyHint = keyHint; }
    public BigDecimal getLowCreditThreshold() { return lowCreditThreshold; }
    public void setLowCreditThreshold(BigDecimal v) { this.lowCreditThreshold = v; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public String getLastError() { return lastError; }
    public Instant getBalanceSyncedAt() { return balanceSyncedAt; }
    public void setBalanceSyncedAt(Instant balanceSyncedAt) { this.balanceSyncedAt = balanceSyncedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
