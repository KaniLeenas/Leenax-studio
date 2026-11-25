package com.leenax.studio.social;

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

/**
 * A connected channel, Page or profile. Its tokens live encrypted in
 * {@link SocialToken}; nothing on this row is secret.
 */
@Entity
@Table(name = "social_accounts")
public class SocialAccount {

    public enum Status { CONNECTED, NEEDS_RECONNECT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SocialPlatform platform;

    /** The platform's id: channel id, Page id, Instagram user id, TikTok open_id. */
    @Column(name = "external_id", nullable = false, length = 120)
    private String externalId;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(length = 120)
    private String username;

    @Column(name = "avatar_url", length = 1000)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.CONNECTED;

    @Column(length = 500)
    private String scopes;

    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

    @Column(name = "refresh_expires_at")
    private Instant refreshExpiresAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /** Non-secret extras, e.g. the Facebook Page an Instagram account is linked to. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> details = new HashMap<>();

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected SocialAccount() {
    }

    public SocialAccount(SocialPlatform platform, String externalId, String displayName) {
        this.platform = platform;
        this.externalId = externalId;
        this.displayName = displayName;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public SocialPlatform getPlatform() { return platform; }
    public String getExternalId() { return externalId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String n) { this.displayName = clip(n, 200, "Untitled"); }
    public String getUsername() { return username; }
    public void setUsername(String u) { this.username = u == null ? null : clip(u, 120, null); }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String u) { this.avatarUrl = u == null || u.length() > 1000 ? null : u; }
    public Status getStatus() { return status; }
    public String getScopes() { return scopes; }
    public void setScopes(String s) { this.scopes = s == null ? null : clip(s, 500, null); }
    public Instant getAccessExpiresAt() { return accessExpiresAt; }
    public void setAccessExpiresAt(Instant t) { this.accessExpiresAt = t; }
    public Instant getRefreshExpiresAt() { return refreshExpiresAt; }
    public void setRefreshExpiresAt(Instant t) { this.refreshExpiresAt = t; }
    public String getLastError() { return lastError; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> d) { this.details = new HashMap<>(d); }
    public Instant getConnectedAt() { return connectedAt; }

    public void markConnected() {
        this.status = Status.CONNECTED;
        this.lastError = null;
        this.connectedAt = Instant.now();
    }

    public void markNeedsReconnect(String reason) {
        this.status = Status.NEEDS_RECONNECT;
        this.lastError = reason == null ? null : clip(reason, 500, null);
    }

    public String detail(String key) {
        Object v = details.get(key);
        return v == null ? null : v.toString();
    }

    private static String clip(String s, int max, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
