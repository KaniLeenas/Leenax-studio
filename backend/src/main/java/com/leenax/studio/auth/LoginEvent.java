package com.leenax.studio.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One sign-in attempt, kept for the login history. */
@Entity
@Table(name = "login_events")
public class LoginEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false)
    private boolean success;

    @Column(length = 40)
    private String reason;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LoginEvent() {
    }

    public LoginEvent(UUID userId, String email, boolean success, String reason, String ipAddress, String userAgent) {
        this.userId = userId;
        this.email = cut(email, 254);
        this.success = success;
        this.reason = reason;
        this.ipAddress = cut(ipAddress, 64);
        this.userAgent = cut(userAgent, 300);
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getEmail() { return email; }
    public boolean isSuccess() { return success; }
    public String getReason() { return reason; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public Instant getCreatedAt() { return createdAt; }
}
