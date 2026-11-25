package com.leenax.studio.ai.provider;

/**
 * A provider call failed. The message is safe to show the user: it never
 * contains the API key or request headers.
 */
public class ProviderException extends RuntimeException {

    private final ProviderErrorKind kind;
    private final int httpStatus;

    public ProviderException(ProviderErrorKind kind, String message) {
        this(kind, message, 0);
    }

    public ProviderException(ProviderErrorKind kind, String message, int httpStatus) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public ProviderErrorKind kind() { return kind; }
    public int httpStatus() { return httpStatus; }
    public boolean retryable() { return kind.retryable(); }
}
