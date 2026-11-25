package com.leenax.studio.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leenax.auth")
public record AuthProperties(String setupToken, int maxFailedLogins, int lockoutMinutes) {

    public AuthProperties {
        if (maxFailedLogins <= 0) maxFailedLogins = 5;
        if (lockoutMinutes <= 0) lockoutMinutes = 15;
    }

    public boolean setupEnabled() {
        return setupToken != null && !setupToken.isBlank();
    }
}
