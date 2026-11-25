package com.leenax.studio.storyboard;

import com.leenax.studio.autoedit.TimelineBuilder;
import com.leenax.studio.projects.Project;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "storyboard_projects")
public class Storyboard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "script_id")
    private UUID scriptId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "aspect_ratio", nullable = false, length = 10)
    private TimelineBuilder.Format aspectRatio = TimelineBuilder.Format.LANDSCAPE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Project.Language language = Project.Language.EN;

    @Column(columnDefinition = "text")
    private String style;

    @Column(name = "narration_media_id")
    private UUID narrationMediaId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "narration_parts", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> narrationParts = new ArrayList<>();

    @Column(name = "timeline_id")
    private UUID timelineId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected Storyboard() {
    }

    public Storyboard(String title, TimelineBuilder.Format aspectRatio, Project.Language language) {
        this.title = title;
        this.aspectRatio = aspectRatio;
        this.language = language;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID p) { this.projectId = p; }
    public UUID getScriptId() { return scriptId; }
    public void setScriptId(UUID s) { this.scriptId = s; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public TimelineBuilder.Format getAspectRatio() { return aspectRatio; }
    public void setAspectRatio(TimelineBuilder.Format a) { this.aspectRatio = a; }
    public Project.Language getLanguage() { return language; }
    public void setLanguage(Project.Language l) { this.language = l; }
    public String getStyle() { return style; }
    public void setStyle(String s) { this.style = s; }
    public UUID getNarrationMediaId() { return narrationMediaId; }
    public void setNarrationMediaId(UUID m) { this.narrationMediaId = m; }
    public List<Map<String, Object>> getNarrationParts() { return narrationParts; }
    public void setNarrationParts(List<Map<String, Object>> p) { this.narrationParts = new ArrayList<>(p); }
    public UUID getTimelineId() { return timelineId; }
    public void setTimelineId(UUID t) { this.timelineId = t; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touchNow() { this.updatedAt = Instant.now(); }
}
