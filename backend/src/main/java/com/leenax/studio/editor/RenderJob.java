package com.leenax.studio.editor;

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
@Table(name = "render_jobs")
public class RenderJob {

    public enum Status { QUEUED, RENDERING, COMPLETED, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "timeline_id", nullable = false)
    private UUID timelineId;

    @Column(name = "timeline_name", nullable = false, length = 160)
    private String timelineName;

    @Column(name = "project_id")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.QUEUED;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal progress = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> settings = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> document = new HashMap<>();

    /** What ffprobe found in the finished file (see RenderValidator). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> validation;

    @Column(name = "output_media_id")
    private UUID outputMediaId;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected RenderJob() {
    }

    public RenderJob(Timeline t, RenderSettings settings) {
        this.timelineId = t.getId();
        this.timelineName = t.getName();
        this.projectId = t.getProjectId();
        this.settings = new HashMap<>(settings.toMap());
        this.document = new HashMap<>(t.getDocument());
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean isActive() {
        return status == Status.QUEUED || status == Status.RENDERING;
    }

    public void setProgress(double p) {
        this.progress = BigDecimal.valueOf(Math.max(0, Math.min(1, p))).setScale(4, RoundingMode.HALF_UP);
    }

    /** Renderer id: the job id is also the id the renderer knows it by. */
    public String rendererId() {
        return id.toString();
    }

    public UUID getId() { return id; }
    public UUID getTimelineId() { return timelineId; }
    public String getTimelineName() { return timelineName; }
    public UUID getProjectId() { return projectId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public BigDecimal getProgress() { return progress; }
    public Map<String, Object> getSettings() { return settings; }
    public Map<String, Object> getDocument() { return document; }
    public UUID getOutputMediaId() { return outputMediaId; }
    public Map<String, Object> getValidation() { return validation; }
    public void setValidation(Map<String, Object> validation) { this.validation = validation; }
    public void setOutputMediaId(UUID outputMediaId) { this.outputMediaId = outputMediaId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String m) { this.errorMessage = m == null ? null : (m.length() > 500 ? m.substring(0, 500) : m); }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant t) { this.nextAttemptAt = t; }
    public void setLockedUntil(Instant t) { this.lockedUntil = t; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant t) { this.submittedAt = t; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant t) { this.completedAt = t; }
    public Instant getCreatedAt() { return createdAt; }
}
