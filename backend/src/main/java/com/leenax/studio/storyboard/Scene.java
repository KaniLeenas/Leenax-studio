package com.leenax.studio.storyboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One storyboard shot, linked to its image, its generated video and its characters. */
@Entity
@Table(name = "storyboard_scenes")
public class Scene {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "storyboard_id", nullable = false)
    private UUID storyboardId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 160)
    private String title = "";

    @Column(nullable = false, columnDefinition = "text")
    private String description = "";

    @Column(nullable = false, columnDefinition = "text")
    private String narration = "";

    /** [{"character": "Kani", "line": "..."}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> dialogue = new ArrayList<>();

    @Column(name = "duration_seconds", nullable = false, precision = 5, scale = 1)
    private BigDecimal durationSeconds = BigDecimal.valueOf(5);

    @Column(name = "camera_angle", length = 40)
    private String cameraAngle;

    @Column(name = "camera_movement", length = 40)
    private String cameraMovement;

    @Column(length = 300)
    private String lighting;

    @Column(length = 300)
    private String composition;

    @Column(name = "sound_notes", columnDefinition = "text")
    private String soundNotes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "character_ids", columnDefinition = "jsonb", nullable = false)
    private List<String> characterIds = new ArrayList<>();

    @Column(name = "image_prompt", columnDefinition = "text")
    private String imagePrompt;

    @Column(name = "video_prompt", columnDefinition = "text")
    private String videoPrompt;

    @Column(name = "image_media_id")
    private UUID imageMediaId;

    @Column(name = "video_media_id")
    private UUID videoMediaId;

    @Column(name = "video_job_id")
    private UUID videoJobId;

    @Column(name = "dialogue_media_id")
    private UUID dialogueMediaId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected Scene() {
    }

    public Scene(UUID storyboardId, int position) {
        this.storyboardId = storyboardId;
        this.position = position;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** A copy placed at {@code position}; the copy keeps the image but not the generated video. */
    public Scene copy(int position) {
        Scene c = new Scene(storyboardId, position);
        c.title = title.length() > 150 ? title.substring(0, 150) : title + (title.isBlank() ? "" : " (copy)");
        c.description = description;
        c.narration = narration;
        c.dialogue = new ArrayList<>(dialogue);
        c.durationSeconds = durationSeconds;
        c.cameraAngle = cameraAngle;
        c.cameraMovement = cameraMovement;
        c.lighting = lighting;
        c.composition = composition;
        c.soundNotes = soundNotes;
        c.characterIds = new ArrayList<>(characterIds);
        c.imagePrompt = imagePrompt;
        c.videoPrompt = videoPrompt;
        c.imageMediaId = imageMediaId;
        return c;
    }

    public UUID getId() { return id; }
    public UUID getStoryboardId() { return storyboardId; }
    public int getPosition() { return position; }
    public void setPosition(int p) { this.position = p; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t == null ? "" : t; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d == null ? "" : d; }
    public String getNarration() { return narration; }
    public void setNarration(String n) { this.narration = n == null ? "" : n; }
    public List<Map<String, Object>> getDialogue() { return dialogue; }
    public void setDialogue(List<Map<String, Object>> d) { this.dialogue = new ArrayList<>(d); }
    public double getDurationSeconds() { return durationSeconds.doubleValue(); }
    public void setDurationSeconds(double s) { this.durationSeconds = BigDecimal.valueOf(Math.max(1, Math.min(120, s))).setScale(1, RoundingMode.HALF_UP); }
    public String getCameraAngle() { return cameraAngle; }
    public void setCameraAngle(String a) { this.cameraAngle = a; }
    public String getCameraMovement() { return cameraMovement; }
    public void setCameraMovement(String m) { this.cameraMovement = m; }
    public String getLighting() { return lighting; }
    public void setLighting(String l) { this.lighting = l; }
    public String getComposition() { return composition; }
    public void setComposition(String c) { this.composition = c; }
    public String getSoundNotes() { return soundNotes; }
    public void setSoundNotes(String s) { this.soundNotes = s; }
    public List<String> getCharacterIds() { return characterIds; }
    public void setCharacterIds(List<String> ids) { this.characterIds = new ArrayList<>(ids); }
    public String getImagePrompt() { return imagePrompt; }
    public void setImagePrompt(String p) { this.imagePrompt = p; }
    public String getVideoPrompt() { return videoPrompt; }
    public void setVideoPrompt(String p) { this.videoPrompt = p; }
    public UUID getImageMediaId() { return imageMediaId; }
    public void setImageMediaId(UUID m) { this.imageMediaId = m; }
    public UUID getVideoMediaId() { return videoMediaId; }
    public void setVideoMediaId(UUID m) { this.videoMediaId = m; }
    public UUID getVideoJobId() { return videoJobId; }
    public void setVideoJobId(UUID j) { this.videoJobId = j; }
    public UUID getDialogueMediaId() { return dialogueMediaId; }
    public void setDialogueMediaId(UUID m) { this.dialogueMediaId = m; }
}
