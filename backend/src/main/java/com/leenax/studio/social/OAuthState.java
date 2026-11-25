package com.leenax.studio.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One sign-in in progress. Only a SHA-256 hash of the random state value is
 * stored, the row is single-use and it expires after ten minutes.
 */
@Entity
@Table(name = "oauth_states")
public class OAuthState {

    @Id
    @Column(name = "state_hash", length = 64)
    private String stateHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private SocialPlatform.AuthProvider provider;

    /** PKCE verifier, for providers that support PKCE. Useless without the one-time code. */
    @Column(name = "code_verifier", length = 128)
    private String codeVerifier;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OAuthState() {
    }

    public OAuthState(String stateHash, SocialPlatform.AuthProvider provider, String codeVerifier, Instant expiresAt) {
        this.stateHash = stateHash;
        this.provider = provider;
        this.codeVerifier = codeVerifier;
        this.expiresAt = expiresAt;
    }

    public SocialPlatform.AuthProvider getProvider() { return provider; }
    public String getCodeVerifier() { return codeVerifier; }
    public Instant getExpiresAt() { return expiresAt; }
}
