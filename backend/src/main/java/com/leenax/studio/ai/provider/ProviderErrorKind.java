package com.leenax.studio.ai.provider;

/** Normalised failure categories shared by every provider. */
public enum ProviderErrorKind {
    INVALID_CREDENTIALS(false),
    INSUFFICIENT_CREDITS(false),
    PROVIDER_UNAVAILABLE(true),
    RATE_LIMIT_EXCEEDED(true),
    UNSUPPORTED_CAPABILITY(false),
    INVALID_REQUEST(false),
    GENERATION_TIMEOUT(false),
    GENERATION_FAILED(false),
    CONTENT_POLICY_REJECTED(false),
    PROVIDER_CONFIGURATION_ERROR(false);

    private final boolean retryable;

    ProviderErrorKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
