package com.leenax.studio.scripts;

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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A script, one version of it. A rewrite makes a new row pointing at its parent. */
@Entity
@Table(name = "scripts")
public class Script {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Project.Language language = Project.Language.EN;

    @Column(name = "video_type", nullable = false, length = 20)
    private String videoType = "YOUTUBE_LONG";

    @Column(columnDefinition = "text")
    private String idea;

    @Column(nullable = false, columnDefinition = "text")
    private String body = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> sections = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "version_no", nullable = false)
    private int versionNo = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected Script() {
    }

    public Script(String title, Project.Language language, String videoType) {
        this.title = title;
        this.language = language;
        this.videoType = videoType;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID p) { this.projectId = p; }
    public UUID getParentId() { return parentId; }
    public void setParentId(UUID p) { this.parentId = p; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public Project.Language getLanguage() { return language; }
    public void setLanguage(Project.Language l) { this.language = l; }
    public String getVideoType() { return videoType; }
    public void setVideoType(String v) { this.videoType = v; }
    public String getIdea() { return idea; }
    public void setIdea(String i) { this.idea = i; }
    public String getBody() { return body; }
    public void setBody(String b) { this.body = b == null ? "" : b; }
    public Map<String, Object> getSections() { return sections; }
    public void setSections(Map<String, Object> s) { this.sections = new HashMap<>(s); }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> m) { this.metadata = new HashMap<>(m); }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int v) { this.versionNo = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
