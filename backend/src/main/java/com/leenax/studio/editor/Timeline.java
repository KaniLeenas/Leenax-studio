package com.leenax.studio.editor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "timelines")
public class Timeline {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false)
    private int fps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> document = new HashMap<>();

    /** Increases with every save; a save based on an older revision is refused. */
    @Column(nullable = false)
    private int revision;

    @Column(name = "duration_seconds", nullable = false, precision = 10, scale = 3)
    private BigDecimal durationSeconds = BigDecimal.ZERO;

    @Column(name = "poster_media_id")
    private UUID posterMediaId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Timeline() {
    }

    public Timeline(String name, int width, int height, int fps, Map<String, Object> document) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.document = new HashMap<>(document);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getFps() { return fps; }
    public void setFormat(int width, int height, int fps) { this.width = width; this.height = height; this.fps = fps; }
    public Map<String, Object> getDocument() { return document; }
    public void setDocument(Map<String, Object> document) { this.document = new HashMap<>(document); }
    public int getRevision() { return revision; }
    public void bumpRevision() { this.revision++; }
    public BigDecimal getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(BigDecimal d) { this.durationSeconds = d; }
    public UUID getPosterMediaId() { return posterMediaId; }
    public void setPosterMediaId(UUID posterMediaId) { this.posterMediaId = posterMediaId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
