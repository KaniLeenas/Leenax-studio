package com.leenax.studio.auth;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A person who can sign in. At most five are active or invited at a time (V9 enforces it);
 * deactivated people keep their history and don't take a seat.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    public enum Role { ADMIN, MEMBER }

    public enum Status { INVITED, ACTIVE, DEACTIVATED }

    /** Null until an invited person sets their password. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Role role = Role.MEMBER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.ACTIVE;

    @Column(name = "can_create_projects", nullable = false)
    private boolean canCreateProjects = true;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** TOTP secret, AES-256-GCM encrypted (see MfaService). Null = two-step sign-in is off. */
    @Column(name = "mfa_secret")
    private byte[] mfaSecret;

    /** Secret shown during set-up, until the first code confirms it. */
    @Column(name = "mfa_pending_secret")
    private byte[] mfaPendingSecret;

    @Column(name = "mfa_enabled_at")
    private Instant mfaEnabledAt;

    /** Last accepted 30-second step, so a code can't be used twice. */
    @Column(name = "mfa_last_step")
    private Long mfaLastStep;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected User() {
    }

    /** The first administrator, made during setup. */
    public static User firstAdmin(String email, String displayName, String passwordHash) {
        User u = new User();
        u.email = email;
        u.displayName = displayName;
        u.passwordHash = passwordHash;
        u.role = Role.ADMIN;
        u.status = Status.ACTIVE;
        u.activatedAt = Instant.now();
        return u;
    }

    /** Someone invited by an administrator: they choose a password through their activation link. */
    public static User invited(String email, String displayName, Role role, boolean canCreateProjects, UUID invitedBy) {
        User u = new User();
        u.email = email;
        u.displayName = displayName;
        u.role = role;
        u.canCreateProjects = canCreateProjects;
        u.status = Status.INVITED;
        u.invitedBy = invitedBy;
        return u;
    }

    public void activate(String passwordHash) {
        this.passwordHash = passwordHash;
        this.status = Status.ACTIVE;
        if (activatedAt == null) activatedAt = Instant.now();
        deactivatedAt = null;
    }

    public void deactivate() {
        status = Status.DEACTIVATED;
        deactivatedAt = Instant.now();
    }

    /** Back to active if they ever set a password, otherwise back to invited. */
    public void reactivate() {
        status = passwordHash == null ? Status.INVITED : Status.ACTIVE;
        deactivatedAt = null;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public byte[] getMfaSecret() { return mfaSecret; }
    public void setMfaSecret(byte[] s) { this.mfaSecret = s; }
    public byte[] getMfaPendingSecret() { return mfaPendingSecret; }
    public void setMfaPendingSecret(byte[] s) { this.mfaPendingSecret = s; }
    public Instant getMfaEnabledAt() { return mfaEnabledAt; }
    public void setMfaEnabledAt(Instant t) { this.mfaEnabledAt = t; }
    public Long getMfaLastStep() { return mfaLastStep; }
    public void setMfaLastStep(Long s) { this.mfaLastStep = s; }
    public boolean isMfaEnabled() { return mfaSecret != null; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Status getStatus() { return status; }
    public boolean isActive() { return status == Status.ACTIVE; }
    public boolean isAdmin() { return role == Role.ADMIN; }
    public boolean canCreateProjects() { return role == Role.ADMIN || canCreateProjects; }
    public boolean getCanCreateProjectsSetting() { return canCreateProjects; }
    public void setCanCreateProjects(boolean v) { this.canCreateProjects = v; }
    public UUID getInvitedBy() { return invitedBy; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getDeactivatedAt() { return deactivatedAt; }
}
