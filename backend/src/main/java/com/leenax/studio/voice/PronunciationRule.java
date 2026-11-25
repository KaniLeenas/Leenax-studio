package com.leenax.studio.voice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** "Say {@code term} as {@code replacement}", applied to every script before synthesis. */
@Entity
@Table(name = "pronunciation_rules")
public class PronunciationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String term;

    @Column(nullable = false, length = 240)
    private String replacement;

    /** EN, TA or null for every language. */
    @Column(length = 5)
    private String language;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PronunciationRule() {
    }

    public PronunciationRule(String term, String replacement, String language) {
        this.term = term;
        this.replacement = replacement;
        this.language = language;
    }

    public UUID getId() { return id; }
    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }
    public String getReplacement() { return replacement; }
    public void setReplacement(String replacement) { this.replacement = replacement; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Instant getCreatedAt() { return createdAt; }
}
