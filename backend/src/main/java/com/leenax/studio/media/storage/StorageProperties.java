package com.leenax.studio.media.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leenax.storage")
public record StorageProperties(
        String bucket,
        String region,
        String endpoint,
        String publicEndpoint,
        String accessKey,
        String secretKey,
        boolean pathStyle,
        boolean createBucket,
        int signedUrlMinutes) {

    public StorageProperties {
        if (signedUrlMinutes <= 0) signedUrlMinutes = 30;
    }

    public boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }

    public boolean hasStaticCredentials() {
        return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    /** Endpoint used to sign URLs that browsers will open. */
    public String signingEndpoint() {
        if (publicEndpoint != null && !publicEndpoint.isBlank()) return publicEndpoint;
        return hasEndpoint() ? endpoint : null;
    }
}
