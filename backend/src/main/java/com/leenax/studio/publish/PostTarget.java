package com.leenax.studio.publish;

import com.leenax.studio.social.SocialPlatform;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One post on one account, with that platform's caption and settings.
 * {@code state} remembers how far the upload got (session URL, container id,
 * publish id) so a retry resumes instead of posting twice.
 */
@Entity
@Table(name = "post_targets")
public class PostTarget {

    public enum Status {
        DRAFT, SCHEDULED, UPLOADING, PROCESSING,
        /** TikTok "upload to inbox": the creator finishes the post in the TikTok app. */
        AWAITING_USER,
        PUBLISHED, FAILED, CANCELLED;

        public static final Set<Status> RUNNING = EnumSet.of(UPLOADING, PROCESSING);
        public static final Set<Status> EDITABLE = EnumSet.of(DRAFT, SCHEDULED, FAILED, CANCELLED);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "account_id")
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SocialPlatform platform;

    @Column(name = "account_name", nullable = false, length = 200)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 14)
    private Status status = Status.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> settings = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> state = new HashMap<>();

    /** When the worker should next look at this target. Null = nothing to do. */
    @Column(name = "run_at")
    private Instant runAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "remote_id", length = 200)
    private String remoteId;

    @Column(name = "remote_url", length = 1000)
    private String remoteUrl;

    @Column(length = 200)
    private String step;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal progress = BigDecimal.ZERO;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(length = 500)
    private String warning;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected PostTarget() {
    }

    public PostTarget(UUID postId, UUID accountId, SocialPlatform platform, String accountName, Map<String, Object> settings) {
        this.postId = postId;
        this.accountId = accountId;
        this.platform = platform;
        this.accountName = accountName;
        this.settings = new HashMap<>(settings);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean isActive() {
        return status == Status.SCHEDULED || Status.RUNNING.contains(status) || status == Status.AWAITING_USER;
    }

    /** Back to the start: used for scheduling, rescheduling and manual retries. */
    public void schedule(Instant at) {
        status = Status.SCHEDULED;
        runAt = at;
        attempts = 0;
        errorMessage = null;
        warning = null;
        step = null;
        progress = BigDecimal.ZERO;
        state = new HashMap<>();
        lockedUntil = null;
    }

    public void toDraft() {
        status = Status.DRAFT;
        runAt = null;
        lockedUntil = null;
    }

    public UUID getId() { return id; }
    public UUID getPostId() { return postId; }
    public UUID getAccountId() { return accountId; }
    public SocialPlatform getPlatform() { return platform; }
    public String getAccountName() { return accountName; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public Map<String, Object> getSettings() { return settings; }
    public void setSettings(Map<String, Object> s) { this.settings = new HashMap<>(s); }
    public Map<String, Object> getState() { return state; }
    public void setState(Map<String, Object> s) { this.state = new HashMap<>(s); }
    public Instant getRunAt() { return runAt; }
    public void setRunAt(Instant t) { this.runAt = t; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int a) { this.attempts = a; }
    public void setLockedUntil(Instant t) { this.lockedUntil = t; }
    public String getRemoteId() { return remoteId; }
    public void setRemoteId(String r) { this.remoteId = clip(r, 200); }
    public String getRemoteUrl() { return remoteUrl; }
    public void setRemoteUrl(String r) { this.remoteUrl = r == null || r.length() > 1000 ? null : r; }
    public String getStep() { return step; }
    public void setStep(String s) { this.step = clip(s, 200); }
    public BigDecimal getProgress() { return progress; }
    public void setProgress(double p) { this.progress = BigDecimal.valueOf(Math.max(0, Math.min(1, p))).setScale(4, RoundingMode.HALF_UP); }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String m) { this.errorMessage = clip(m, 500); }
    public String getWarning() { return warning; }
    public void setWarning(String w) { this.warning = clip(w, 500); }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant t) { this.publishedAt = t; }
    public Instant getCreatedAt() { return createdAt; }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
