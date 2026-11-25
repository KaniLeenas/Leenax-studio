package com.leenax.studio.ai.credits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One balance bucket: free trial credits, a paid pack, a monthly allowance... */
@Entity
@Table(name = "credit_balances")
public class CreditBalance {

    public enum Kind { FREE, PAID }

    public enum Source { MANUAL, PROVIDER }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind = Kind.PAID;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String unit;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source = Source.MANUAL;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CreditBalance() {
    }

    public CreditBalance(UUID accountId, String label, Kind kind, BigDecimal amount, String unit, Instant expiresAt,
                         Source source) {
        this.accountId = accountId;
        this.label = label;
        this.kind = kind;
        this.amount = amount;
        this.unit = unit;
        this.expiresAt = expiresAt;
        this.source = source;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Source getSource() { return source; }
    public Instant getUpdatedAt() { return updatedAt; }
}
