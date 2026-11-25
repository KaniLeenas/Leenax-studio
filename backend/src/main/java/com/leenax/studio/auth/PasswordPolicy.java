package com.leenax.studio.auth;

import com.leenax.studio.common.ApiException;

import java.nio.charset.StandardCharsets;

/**
 * Owner password rules. BCrypt only reads the first 72 bytes, so longer
 * passwords are rejected rather than silently truncated.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw ApiException.badRequest("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.badRequest("Password must be at most " + MAX_BYTES + " bytes");
        }
        if (password.isBlank()) {
            throw ApiException.badRequest("Password cannot be only spaces");
        }
    }
}
