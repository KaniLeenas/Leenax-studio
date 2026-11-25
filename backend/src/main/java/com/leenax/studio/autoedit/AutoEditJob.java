package com.leenax.studio.autoedit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "auto_edit_jobs")
public class AutoEditJob {

    public enum Mode { SCRIPT_TO_VIDEO, TALKING_HEAD, HIGHLIGHTS }

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Mode mode;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "project_id")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.QUEUED;

    @Column(length = 160)
    private String step;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal progress = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> settings = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected AutoEditJob() {
    }

    public AutoEditJob(Mode mode, String name, UUID projectId, Map<String, Object> settings) {
        this.mode = mode;
        this.name = name;
        this.projectId = projectId;
        this.settings = new HashMap<>(settings);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean isActive() {
        return status == Status.QUEUED || status == Status.RUNNING;
    }

    public UUID getId() { return id; }
    public Mode getMode() { return mode; }
    public String getName() { return name; }
    public UUID getProjectId() { return projectId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getStep() { return step; }
    public void setStep(String step) { this.step = step == null ? null : (step.length() > 160 ? step.substring(0, 160) : step); }
    public BigDecimal getProgress() { return progress; }
    public void setProgress(double p) { this.progress = BigDecimal.valueOf(Math.max(0, Math.min(1, p))).setScale(4, RoundingMode.HALF_UP); }
    public Map<String, Object> getSettings() { return settings; }
    public Map<String, Object> getResult() { return result; }
    public void setResult(Map<String, Object> result) { this.result = result; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String m) { this.errorMessage = m == null ? null : (m.length() > 500 ? m.substring(0, 500) : m); }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public void setLockedUntil(Instant t) { this.lockedUntil = t; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant t) { this.startedAt = t; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant t) { this.completedAt = t; }
    public Instant getCreatedAt() { return createdAt; }
}
