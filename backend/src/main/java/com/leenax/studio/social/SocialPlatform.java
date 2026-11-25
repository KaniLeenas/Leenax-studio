package com.leenax.studio.social;

/**
 * Platforms the publisher posts to, each through its official API and its
 * official sign-in. Facebook and Instagram share one Meta sign-in.
 */
public enum SocialPlatform {
    YOUTUBE("YouTube", AuthProvider.GOOGLE),
    FACEBOOK("Facebook Page", AuthProvider.META),
    INSTAGRAM("Instagram", AuthProvider.META),
    TIKTOK("TikTok", AuthProvider.TIKTOK);

    /** Whose sign-in page connects the platform. */
    public enum AuthProvider { GOOGLE, META, TIKTOK }

    private final String displayName;
    private final AuthProvider authProvider;

    SocialPlatform(String displayName, AuthProvider authProvider) {
        this.displayName = displayName;
        this.authProvider = authProvider;
    }

    public String displayName() { return displayName; }
    public AuthProvider authProvider() { return authProvider; }
}
