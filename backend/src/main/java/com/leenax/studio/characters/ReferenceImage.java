package com.leenax.studio.characters;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A reference image for a character or for one storyboard scene. */
@Entity
@Table(name = "storyboard_references")
public class ReferenceImage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "character_id")
    private UUID characterId;

    @Column(name = "scene_id")
    private UUID sceneId;

    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Column(length = 60)
    private String label;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReferenceImage() {
    }

    public static ReferenceImage forCharacter(UUID characterId, UUID mediaId, int position) {
        ReferenceImage r = new ReferenceImage();
        r.characterId = characterId;
        r.mediaId = mediaId;
        r.position = position;
        return r;
    }

    public static ReferenceImage forScene(UUID sceneId, UUID mediaId, int position) {
        ReferenceImage r = new ReferenceImage();
        r.sceneId = sceneId;
        r.mediaId = mediaId;
        r.position = position;
        return r;
    }

    public UUID getId() { return id; }
    public UUID getCharacterId() { return characterId; }
    public UUID getSceneId() { return sceneId; }
    public UUID getMediaId() { return mediaId; }
    public int getPosition() { return position; }
}
