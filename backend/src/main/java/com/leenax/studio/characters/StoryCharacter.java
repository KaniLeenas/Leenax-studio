package com.leenax.studio.characters;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** A reusable character: how they look and sound. projectId null = usable in every project. */
@Entity
@Table(name = "storyboard_characters")
public class StoryCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String appearance;

    @Column(columnDefinition = "text")
    private String clothing;

    @Column(name = "voice_profile_id")
    private UUID voiceProfileId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected StoryCharacter() {
    }

    public StoryCharacter(String name) {
        this.name = name;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID p) { this.projectId = p; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getAppearance() { return appearance; }
    public void setAppearance(String a) { this.appearance = a; }
    public String getClothing() { return clothing; }
    public void setClothing(String c) { this.clothing = c; }
    public UUID getVoiceProfileId() { return voiceProfileId; }
    public void setVoiceProfileId(UUID v) { this.voiceProfileId = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** How the character is described to an image or video model. */
    public String visualBrief() {
        StringBuilder sb = new StringBuilder(name);
        if (appearance != null && !appearance.isBlank()) sb.append(": ").append(appearance.strip());
        if (clothing != null && !clothing.isBlank()) sb.append("; wearing ").append(clothing.strip());
        return sb.toString();
    }
}
