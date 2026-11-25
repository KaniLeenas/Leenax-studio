package com.leenax.studio.voice;

import com.leenax.studio.ai.provider.ProviderCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A saved voice: a provider voice plus your preferred style, speed and pitch. */
@Entity
@Table(name = "voice_profiles")
public class VoiceProfile {

    public enum Kind { STOCK, CLONED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProviderCode provider;

    @Column(name = "provider_voice_id", nullable = false, length = 120)
    private String providerVoiceId;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(length = 5)
    private String language;

    @Column(name = "model_code", length = 120)
    private String modelCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> settings = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> consent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sample_media_ids", columnDefinition = "jsonb", nullable = false)
    private List<String> sampleMediaIds = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Everyone may use a shared voice (a team narrator); only its creator changes it. */
    @Column(nullable = false)
    private boolean shared;

    /** Set by the database from the acting person (V9); never written by the app. */
    @Column(name = "created_by", insertable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected VoiceProfile() {
    }

    public VoiceProfile(UUID accountId, ProviderCode provider, String providerVoiceId, String name, Kind kind) {
        this.accountId = accountId;
        this.provider = provider;
        this.providerVoiceId = providerVoiceId;
        this.name = name;
        this.kind = kind;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public ProviderCode getProvider() { return provider; }
    public String getProviderVoiceId() { return providerVoiceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Kind getKind() { return kind; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public Map<String, Object> getSettings() { return settings; }
    public void setSettings(Map<String, Object> settings) { this.settings = new HashMap<>(settings); }
    public Map<String, Object> getConsent() { return consent; }
    public void setConsent(Map<String, Object> consent) { this.consent = consent == null ? null : new HashMap<>(consent); }
    public List<String> getSampleMediaIds() { return sampleMediaIds; }
    public void setSampleMediaIds(List<String> ids) { this.sampleMediaIds = new ArrayList<>(ids); }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isShared() { return shared; }
    public void setShared(boolean shared) { this.shared = shared; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
