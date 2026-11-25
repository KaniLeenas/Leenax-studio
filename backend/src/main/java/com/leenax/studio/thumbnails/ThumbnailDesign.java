package com.leenax.studio.thumbnails;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A layered Thumbnail Studio design. The document is the editor's own JSON (layers, colours, fonts). */
@Entity
@Table(name = "thumbnail_designs")
public class ThumbnailDesign {

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> document = new HashMap<>();

    @Column(name = "is_template", nullable = false)
    private boolean template;

    @Column(name = "export_media_id")
    private UUID exportMediaId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ThumbnailDesign() {
    }

    public ThumbnailDesign(String name, int width, int height, Map<String, Object> document) {
        this.name = name;
        this.width = width;
        this.height = height;
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
    public void setSize(int width, int height) { this.width = width; this.height = height; }
    public Map<String, Object> getDocument() { return document; }
    public void setDocument(Map<String, Object> document) { this.document = new HashMap<>(document); }
    public boolean isTemplate() { return template; }
    public void setTemplate(boolean template) { this.template = template; }
    public UUID getExportMediaId() { return exportMediaId; }
    public void setExportMediaId(UUID exportMediaId) { this.exportMediaId = exportMediaId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
