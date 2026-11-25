package com.leenax.studio.ai.provider;

/** Decrypted credentials, held only in memory for the duration of a call. */
public record ProviderCredentials(String apiKey) {

    public ProviderCredentials {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("API key is empty");
        apiKey = apiKey.strip();
    }

    @Override
    public String toString() {
        return "ProviderCredentials[***]";
    }
}
