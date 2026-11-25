package com.leenax.studio.subtitles;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "subtitle_tracks")
public class SubtitleTrack {

    public enum Source { TRANSCRIBED, TRANSLATED, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(nullable = false, length = 10)
    private String language;

    @Column(nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Source source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Cue> segments = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SubtitleTrack() {
    }

    public SubtitleTrack(UUID mediaId, UUID projectId, String language, String label, Source source, List<Cue> segments) {
        this.mediaId = mediaId;
        this.projectId = projectId;
        this.language = language;
        this.label = label;
        this.source = source;
        this.segments = new ArrayList<>(segments);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getMediaId() { return mediaId; }
    public UUID getProjectId() { return projectId; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Source getSource() { return source; }
    public List<Cue> getSegments() { return segments; }
    public void setSegments(List<Cue> segments) { this.segments = new ArrayList<>(segments); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
