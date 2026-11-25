package com.leenax.studio.autoedit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "media_analyses")
public class MediaAnalysis {

    @Id
    @Column(name = "media_id")
    private UUID mediaId;

    @Column(length = 10)
    private String language;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> transcript;

    @Column(name = "transcript_provider", length = 30)
    private String transcriptProvider;

    @Column(name = "transcribed_at")
    private Instant transcribedAt;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "description_model", length = 120)
    private String descriptionModel;

    @Column(name = "described_at")
    private Instant describedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected MediaAnalysis() {
    }

    public MediaAnalysis(UUID mediaId) {
        this.mediaId = mediaId;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getMediaId() { return mediaId; }
    public String getLanguage() { return language; }
    public Map<String, Object> getTranscript() { return transcript; }
    public String getTranscriptProvider() { return transcriptProvider; }
    public String getDescription() { return description; }
    public String getDescriptionModel() { return descriptionModel; }

    public void setTranscript(Map<String, Object> transcript, String language, String provider) {
        this.transcript = transcript;
        this.language = language;
        this.transcriptProvider = provider;
        this.transcribedAt = Instant.now();
    }

    public void setDescription(String description, String model) {
        this.description = description;
        this.descriptionModel = model;
        this.describedAt = Instant.now();
    }
}
