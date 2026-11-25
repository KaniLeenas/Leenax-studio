package com.leenax.studio.ai.credits;

import com.leenax.studio.ai.provider.ProviderCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_logs")
public class UsageLog {

    public enum CostSource { PROVIDER_REPORTED, ESTIMATED, UNKNOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "account_name", nullable = false, length = 80)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProviderCode provider;

    @Column(name = "model_code", nullable = false, length = 120)
    private String modelCode;

    @Column(nullable = false, length = 20)
    private String capability;

    @Column(name = "generation_job_id")
    private UUID generationJobId;

    @Column(name = "output_seconds", precision = 10, scale = 2)
    private BigDecimal outputSeconds;

    @Column(precision = 14, scale = 2)
    private BigDecimal cost;

    @Column(name = "cost_unit", length = 20)
    private String costUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_source", nullable = false, length = 20)
    private CostSource costSource;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected UsageLog() {
    }

    public UsageLog(UUID accountId, String accountName, ProviderCode provider, String modelCode, String capability,
                    UUID generationJobId, BigDecimal outputSeconds, BigDecimal cost, String costUnit,
                    CostSource costSource, boolean success) {
        this.accountId = accountId;
        this.accountName = accountName;
        this.provider = provider;
        this.modelCode = modelCode;
        this.capability = capability;
        this.generationJobId = generationJobId;
        this.outputSeconds = outputSeconds;
        this.cost = cost;
        this.costUnit = costUnit;
        this.costSource = costSource;
        this.success = success;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public String getAccountName() { return accountName; }
    public ProviderCode getProvider() { return provider; }
    public String getModelCode() { return modelCode; }
    public String getCapability() { return capability; }
    public UUID getGenerationJobId() { return generationJobId; }
    public BigDecimal getOutputSeconds() { return outputSeconds; }
    public BigDecimal getCost() { return cost; }
    public String getCostUnit() { return costUnit; }
    public CostSource getCostSource() { return costSource; }
    public boolean isSuccess() { return success; }
    public Instant getCreatedAt() { return createdAt; }
}
