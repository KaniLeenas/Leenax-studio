package com.leenax.studio.social;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SocialDtos {

    private SocialDtos() {
    }

    public record PlatformInfo(SocialPlatform platform, String name, SocialPlatform.AuthProvider signIn,
                               boolean configured, String redirectUri, String setupHint, List<String> limits) {
    }

    public record AccountResponse(UUID id, SocialPlatform platform, String displayName, String username,
                                  String avatarUrl, String profileUrl, SocialAccount.Status status, String lastError,
                                  Instant accessExpiresAt, Instant connectedAt) {

        static AccountResponse of(SocialAccount a) {
            return new AccountResponse(a.getId(), a.getPlatform(), a.getDisplayName(), a.getUsername(), a.getAvatarUrl(),
                    profileUrl(a), a.getStatus(), a.getLastError(),
                    // Only show an expiry the user has to act on (one without automatic renewal).
                    a.getRefreshExpiresAt() != null ? a.getRefreshExpiresAt() : a.getAccessExpiresAt(),
                    a.getConnectedAt());
        }

        static String profileUrl(SocialAccount a) {
            return switch (a.getPlatform()) {
                case YOUTUBE -> "https://www.youtube.com/channel/" + a.getExternalId();
                case FACEBOOK -> "https://www.facebook.com/" + a.getExternalId();
                case INSTAGRAM -> a.getUsername() == null ? null : "https://www.instagram.com/" + a.getUsername();
                case TIKTOK -> a.getUsername() == null ? null : "https://www.tiktok.com/@" + a.getUsername();
            };
        }
    }

    public record StartResponse(String authorizationUrl) {
    }

    public record CreatorInfoResponse(String username, String nickname, String avatarUrl, List<String> privacyOptions,
                                      boolean commentDisabled, boolean duetDisabled, boolean stitchDisabled,
                                      int maxVideoSeconds) {
        static CreatorInfoResponse of(TikTokClient.CreatorInfo c) {
            return new CreatorInfoResponse(c.username(), c.nickname(), c.avatarUrl(), c.privacyOptions(),
                    c.commentDisabled(), c.duetDisabled(), c.stitchDisabled(), c.maxVideoSeconds());
        }
    }
}
