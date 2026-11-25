package com.leenax.studio.ai.generation;

import com.leenax.studio.ai.provider.ProviderCode;
import com.leenax.studio.ai.provider.VideoRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "generation_jobs")
public class GenerationJob {

    public enum Status {
        QUEUED, PROCESSING, COMPLETED, FAILED, CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "variation_index", nullable = false)
    private short variationIndex;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "account_name", nullable = false, length = 80)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProviderCode provider;

    @Column(name = "model_id")
    private UUID modelId;

    @Column(name = "model_code", nullable = false, length = 120)
    private String modelCode;

    @Column(name = "model_name", nullable = false, length = 160)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VideoRequest.Mode mode;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    @Column(name = "negative_prompt", columnDefinition = "text")
    private String negativePrompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> params = new HashMap<>();

    @Column(name = "input_media_id")
    private UUID inputMediaId;

    @Column(name = "project_id")
    private UUID projectId;

    /** Set by the database from the acting person (V9); never written by the app. */
    @Column(name = "created_by", insertable = false, updatable = false)
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(name = "provider_job_id", length = 200)
    private String providerJobId;

    @Column(precision = 5, scale = 4)
    private BigDecimal progress;

    @Column(nullable = false)
    private short attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "estimated_cost", precision = 14, scale = 2)
    private BigDecimal estimatedCost;

    @Column(name = "actual_cost", precision = 14, scale = 2)
    private BigDecimal actualCost;

    @Column(name = "cost_unit", length = 20)
    private String costUnit;

    @Column(name = "output_media_id")
    private UUID outputMediaId;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected GenerationJob() {
    }

    public GenerationJob(UUID groupId, int variationIndex) {
        this.groupId = groupId;
        this.variationIndex = (short) variationIndex;
    }

    // ---- state helpers ---------------------------------------------------------

    public void scheduleNext(int seconds) {
        nextAttemptAt = Instant.now().plusSeconds(seconds);
        lockedUntil = null;
    }

    public void fail(String code, String message) {
        status = Status.FAILED;
        errorCode = code;
        errorMessage = message == null ? null : (message.length() > 500 ? message.substring(0, 499) + "…" : message);
        completedAt = Instant.now();
        lockedUntil = null;
    }

    public void noteRetry(String message, int delaySeconds) {
        attempts++;
        errorMessage = message;
        scheduleNext(delaySeconds);
    }

    // ---- accessors -------------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getGroupId() { return groupId; }
    public int getVariationIndex() { return variationIndex; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public ProviderCode getProvider() { return provider; }
    public void setProvider(ProviderCode provider) { this.provider = provider; }
    public UUID getModelId() { return modelId; }
    public void setModelId(UUID modelId) { this.modelId = modelId; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public VideoRequest.Mode getMode() { return mode; }
    public void setMode(VideoRequest.Mode mode) { this.mode = mode; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = new HashMap<>(params); }
    public UUID getInputMediaId() { return inputMediaId; }
    public void setInputMediaId(UUID inputMediaId) { this.inputMediaId = inputMediaId; }
    public UUID getProjectId() { return projectId; }
    public UUID getCreatedBy() { return createdBy; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getProviderJobId() { return providerJobId; }
    public void setProviderJobId(String providerJobId) { this.providerJobId = providerJobId; }
    public BigDecimal getProgress() { return progress; }
    public void setProgress(Double p) { this.progress = p == null ? null : BigDecimal.valueOf(Math.max(0, Math.min(1, p))).setScale(4, java.math.RoundingMode.HALF_UP); }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void clearError() { this.errorMessage = null; this.errorCode = null; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }
    public BigDecimal getActualCost() { return actualCost; }
    public void setActualCost(BigDecimal actualCost) { this.actualCost = actualCost; }
    public String getCostUnit() { return costUnit; }
    public void setCostUnit(String costUnit) { this.costUnit = costUnit; }
    public UUID getOutputMediaId() { return outputMediaId; }
    public void setOutputMediaId(UUID outputMediaId) { this.outputMediaId = outputMediaId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    // ---- typed params ----------------------------------------------------------

    public Integer durationSeconds() {
        return params.get("durationSeconds") instanceof Number n ? n.intValue() : null;
    }

    public String aspectRatio() {
        return params.get("aspectRatio") instanceof String s ? s : null;
    }

    public String resolution() {
        return params.get("resolution") instanceof String s ? s : null;
    }

    public Boolean audio() {
        return params.get("audio") instanceof Boolean b ? b : null;
    }
}
