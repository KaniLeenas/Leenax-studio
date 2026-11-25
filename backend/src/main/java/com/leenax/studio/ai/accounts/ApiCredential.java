package com.leenax.studio.ai.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Encrypted API key for one account. Never serialised to the frontend. */
@Entity
@Table(name = "api_credentials")
public class ApiCredential {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(nullable = false)
    private byte[] ciphertext;

    @Column(name = "key_version", nullable = false)
    private short keyVersion = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    protected ApiCredential() {
    }

    public ApiCredential(UUID accountId, byte[] ciphertext) {
        this.accountId = accountId;
        this.ciphertext = ciphertext;
    }

    public UUID getAccountId() { return accountId; }
    public byte[] getCiphertext() { return ciphertext; }

    public void rotate(byte[] newCiphertext) {
        this.ciphertext = newCiphertext;
        this.rotatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "ApiCredential[" + accountId + "]";
    }
}
