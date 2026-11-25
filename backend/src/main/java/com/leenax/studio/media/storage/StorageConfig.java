package com.leenax.studio.media.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/** Works with AWS S3 (leave endpoint blank) or any S3-compatible store such as MinIO. */
@Configuration
public class StorageConfig {

    @Bean
    AwsCredentialsProvider storageCredentials(StorageProperties props) {
        if (props.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        }
        return DefaultCredentialsProvider.create();
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(StorageProperties props, AwsCredentialsProvider storageCredentials) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(storageCredentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        if (props.hasEndpoint()) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @org.springframework.context.annotation.Primary
    S3Presigner s3Presigner(StorageProperties props, AwsCredentialsProvider storageCredentials) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(storageCredentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        String signingEndpoint = props.signingEndpoint();
        if (signingEndpoint != null) {
            builder.endpointOverride(URI.create(signingEndpoint));
        }
        return builder.build();
    }

    /**
     * Signs links for services inside the Docker network (the renderer), using the
     * internal storage address instead of the one browsers use.
     */
    @Bean(name = "internalPresigner", destroyMethod = "close")
    S3Presigner internalPresigner(StorageProperties props, AwsCredentialsProvider storageCredentials) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(storageCredentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        if (props.hasEndpoint()) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        return builder.build();
    }
}
