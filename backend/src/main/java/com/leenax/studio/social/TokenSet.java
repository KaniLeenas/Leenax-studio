package com.leenax.studio.social;

import java.time.Instant;

/**
 * Tokens as returned by a platform. {@code refreshToken} is null when the
 * platform did not issue a new one (keep the stored one). Never log this.
 */
public record TokenSet(String accessToken, String refreshToken, Instant accessExpiresAt,
                       Instant refreshExpiresAt, String scopes) {

    @Override
    public String toString() {
        return "TokenSet[expires " + accessExpiresAt + "]";
    }
}
