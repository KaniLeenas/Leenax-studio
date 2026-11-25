package com.leenax.studio.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Encrypted OAuth tokens for one social account. Never serialised to the frontend. */
@Entity
@Table(name = "social_tokens")
public class SocialToken {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "access_ciphertext", nullable = false)
    private byte[] accessCiphertext;

    @Column(name = "refresh_ciphertext")
    private byte[] refreshCiphertext;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SocialToken() {
    }

    public SocialToken(UUID accountId, byte[] access, byte[] refresh) {
        this.accountId = accountId;
        this.accessCiphertext = access;
        this.refreshCiphertext = refresh;
    }

    public UUID getAccountId() { return accountId; }
    public byte[] getAccessCiphertext() { return accessCiphertext; }
    public byte[] getRefreshCiphertext() { return refreshCiphertext; }

    public void replace(byte[] access, byte[] refresh) {
        this.accessCiphertext = access;
        if (refresh != null) this.refreshCiphertext = refresh;
        this.updatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "SocialToken[" + accountId + "]";
    }
}
