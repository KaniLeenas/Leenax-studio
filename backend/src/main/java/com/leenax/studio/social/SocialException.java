package com.leenax.studio.social;

import java.time.Duration;

/**
 * A platform call failed. The message comes from the platform's response (or
 * our own wording) and never contains tokens or request headers.
 */
public class SocialException extends RuntimeException {

    public enum Kind {
        /** Sign-in expired, was revoked, or lacks a permission: the account must be reconnected. */
        RECONNECT,
        /** Rate limit or daily cap: try again later. */
        RATE_LIMITED,
        /** Network trouble or a server error on the platform side: retry soon. */
        TEMPORARY,
        /** The platform refused this post (bad file, policy, unsupported setting). Retrying will not help. */
        REJECTED,
        /** The app is not set up for this platform (missing client id/secret). */
        NOT_CONFIGURED
    }

    private final Kind kind;
    private final Duration retryAfter;

    public SocialException(Kind kind, String message) {
        this(kind, message, null);
    }

    public SocialException(Kind kind, String message, Duration retryAfter) {
        super(message);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() { return kind; }

    public Duration retryAfter() { return retryAfter; }

    public boolean retryable() {
        return kind == Kind.RATE_LIMITED || kind == Kind.TEMPORARY;
    }
}
