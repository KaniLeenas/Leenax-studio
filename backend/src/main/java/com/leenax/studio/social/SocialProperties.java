package com.leenax.studio.social;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App credentials for the platforms' official sign-in. A platform whose
 * client id or secret is blank is shown as "not set up" and cannot be connected.
 *
 * {@code publicBaseUrl} is the address the browser uses for the studio (for
 * example https://studio.example.com). Sign-in pages send the browser back to
 * {@code <publicBaseUrl>/api/v1/social/oauth/<provider>/callback}, and that exact
 * address must be registered in each developer console.
 */
@ConfigurationProperties(prefix = "leenax.social")
public record SocialProperties(
        String publicBaseUrl,
        Google google,
        Meta meta,
        TikTok tiktok,
        Endpoints endpoints) {

    public record Google(String clientId, String clientSecret) {
        public boolean configured() { return notBlank(clientId) && notBlank(clientSecret); }
    }

    public record Meta(String appId, String appSecret, String graphVersion) {
        public boolean configured() { return notBlank(appId) && notBlank(appSecret); }
        public String version() { return notBlank(graphVersion) ? graphVersion.strip() : "v26.0"; }
    }

    public record TikTok(String clientKey, String clientSecret) {
        public boolean configured() { return notBlank(clientKey) && notBlank(clientSecret); }
    }

    /** Official API hosts. Only tests point these somewhere else. */
    public record Endpoints(String googleAuth, String googleToken, String youtubeApi, String youtubeUpload,
                            String facebookDialog, String graph, String rupload,
                            String tiktokAuth, String tiktokApi, String youtubeAnalytics, String youtubeReporting) {

        public Endpoints {
            youtubeAnalytics = youtubeAnalytics == null ? "https://youtubeanalytics.googleapis.com/v2" : youtubeAnalytics;
            youtubeReporting = youtubeReporting == null ? "https://youtubereporting.googleapis.com/v1" : youtubeReporting;
        }

        public static Endpoints official() {
            return new Endpoints(
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com",
                    "https://www.googleapis.com/youtube/v3",
                    "https://www.googleapis.com/upload/youtube/v3",
                    "https://www.facebook.com",
                    "https://graph.facebook.com",
                    "https://rupload.facebook.com",
                    "https://www.tiktok.com/v2/auth/authorize/",
                    "https://open.tiktokapis.com",
                    "https://youtubeanalytics.googleapis.com/v2",
                    "https://youtubereporting.googleapis.com/v1");
        }

        /** Every endpoint on one mock host (tests). */
        public static Endpoints at(String base) {
            return new Endpoints(base + "/google/auth", base, base + "/youtube/v3", base + "/upload/youtube/v3",
                    base + "/fb", base, base + "/rupload", base + "/tiktok/auth", base, base + "/ytanalytics/v2", base + "/ytreporting/v1");
        }
    }

    public SocialProperties {
        google = google == null ? new Google(null, null) : google;
        meta = meta == null ? new Meta(null, null, null) : meta;
        tiktok = tiktok == null ? new TikTok(null, null) : tiktok;
        endpoints = endpoints == null || endpoints.graph() == null ? Endpoints.official() : endpoints;
        publicBaseUrl = notBlank(publicBaseUrl) ? publicBaseUrl.strip().replaceAll("/+$", "") : "http://localhost:8088";
    }

    public boolean configured(SocialPlatform.AuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> google.configured();
            case META -> meta.configured();
            case TIKTOK -> tiktok.configured();
        };
    }

    public String redirectUri(SocialPlatform.AuthProvider provider) {
        return publicBaseUrl + "/api/v1/social/oauth/" + provider.name().toLowerCase() + "/callback";
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
