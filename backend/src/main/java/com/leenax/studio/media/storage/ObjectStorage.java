package com.leenax.studio.media.storage;

import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Private object storage. The bucket is never public: browsers only receive
 * short-lived signed URLs from {@link #signedUrl}.
 */
@Service
public class ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties props;
    private final S3Presigner internalPresigner;

    public ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties props,
                         @org.springframework.beans.factory.annotation.Qualifier("internalPresigner") S3Presigner internalPresigner) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
        this.internalPresigner = internalPresigner;
    }

    /** Signed GET link for internal services (the renderer). Never sent to a browser. */
    public String internalSignedUrl(String key, Duration validFor) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(validFor)
                .getObjectRequest(GetObjectRequest.builder().bucket(props.bucket()).key(key).build())
                .build();
        return internalPresigner.presignGetObject(request).url().toString();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404 && props.createBucket()) {
                s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
                log.info("Created private bucket {}", props.bucket());
            } else {
                log.error("Storage bucket {} is not reachable (status {})", props.bucket(), e.statusCode());
            }
        } catch (SdkException e) {
            log.error("Storage is not reachable: {}", e.getClass().getSimpleName());
        }
    }

    /** True when the bucket answers (system health page). */
    public boolean reachable() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
            return true;
        } catch (SdkException e) {
            return false;
        }
    }

    public void put(String key, Path file, String contentType) {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromFile(file));
        } catch (SdkException e) {
            log.error("Upload to storage failed for key {}: {}", key, e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.STORAGE_ERROR, "Could not save the file to storage");
        }
    }

    /** Reads a small object fully into memory (used for source images up to a few MB). */
    public byte[] read(String key, long maxBytes) {
        try (var in = s3.getObject(GetObjectRequest.builder().bucket(props.bucket()).key(key).build())) {
            byte[] bytes = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
            if (bytes.length > maxBytes) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "File is too large");
            }
            return bytes;
        } catch (java.io.IOException | SdkException e) {
            log.error("Read from storage failed for key {}: {}", key, e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.STORAGE_ERROR, "Could not read the file from storage");
        }
    }

    /** Copies an object to a local file (for FFmpeg and provider uploads). */
    public void download(String key, Path target) {
        try (var in = s3.getObject(GetObjectRequest.builder().bucket(props.bucket()).key(key).build())) {
            java.nio.file.Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException | SdkException e) {
            log.error("Download from storage failed for key {}: {}", key, e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.STORAGE_ERROR, "Could not read the file from storage");
        }
    }

    /** Opens an object for streaming to the browser. The caller closes the stream. */
    public java.io.InputStream open(String key) {
        try {
            return s3.getObject(GetObjectRequest.builder().bucket(props.bucket()).key(key).build());
        } catch (SdkException e) {
            log.error("Open from storage failed for key {}: {}", key, e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.STORAGE_ERROR, "Could not read the file from storage");
        }
    }

    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket()).key(key).build());
        } catch (SdkException e) {
            // Orphaned objects are harmless; log and continue so the DB row can still be removed.
            log.warn("Could not delete storage object {}: {}", key, e.getClass().getSimpleName());
        }
    }

    /**
     * Short-lived signed GET URL.
     *
     * @param downloadName when non-null the browser downloads with this filename; otherwise the file is shown inline.
     */
    public String signedUrl(String key, String downloadName) {
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(props.bucket()).key(key);
        if (downloadName != null) {
            get.responseContentDisposition(contentDisposition(downloadName));
        }
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(props.signedUrlMinutes()))
                .getObjectRequest(get.build())
                .build();
        return presigner.presignGetObject(request).url().toString();
    }

    static String contentDisposition(String filename) {
        String ascii = filename.replaceAll("[^A-Za-z0-9._ -]", "_");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
