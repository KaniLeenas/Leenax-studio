package com.leenax.studio.social;

import java.util.Map;

/** Who a token belongs to, as reported by the platform. */
public record Profile(SocialPlatform platform, String externalId, String displayName, String username,
                      String avatarUrl, Map<String, Object> details) {

    public Profile(SocialPlatform platform, String externalId, String displayName, String username, String avatarUrl) {
        this(platform, externalId, displayName, username, avatarUrl, Map.of());
    }
}
