package com.leenax.studio.publish;

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

import java.time.Instant;
import java.util.UUID;

/** One video going to one or more accounts. Each account is a {@link PostTarget}. */
@Entity
@Table(name = "posts")
public class Post {

    public enum Status { DRAFT, SCHEDULED, PUBLISHING, PUBLISHED, PARTIAL, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(name = "media_id")
    private UUID mediaId;

    @Column(name = "thumbnail_media_id")
    private UUID thumbnailMediaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.DRAFT;

    /** When it goes out (scheduled) or is planned to (draft). Null = publish now / undated draft. */
    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected Post() {
    }

    public Post(String title, UUID mediaId, UUID thumbnailMediaId) {
        this.title = title;
        this.mediaId = mediaId;
        this.thumbnailMediaId = thumbnailMediaId;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public UUID getMediaId() { return mediaId; }
    public void setMediaId(UUID m) { this.mediaId = m; }
    public UUID getThumbnailMediaId() { return thumbnailMediaId; }
    public void setThumbnailMediaId(UUID m) { this.thumbnailMediaId = m; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant t) { this.scheduledAt = t; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant t) { this.publishedAt = t; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
