package com.leenax.studio.images;

import com.leenax.studio.ai.provider.Multipart;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls the private image-tools service (rembg, runs on your own server — no
 * provider account or credits). It is reachable only inside the Docker network.
 */
@Component
public class BackgroundRemover {

    private final String baseUrl;
    private final HttpClient client;

    public BackgroundRemover(@Value("${leenax.imagetools.url:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean configured() {
        return !baseUrl.isBlank();
    }

    /** Returns a PNG with a transparent background. */
    public byte[] removeBackground(byte[] image, String filename, String contentType) {
        if (!configured()) {
            throw new ApiException(ErrorCode.CONFLICT, "Background removal isn't set up. Start the imagetools service (see README).");
        }
        Multipart form = new Multipart().file("file", filename, contentType, image);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/remove-background"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", form.contentType())
                .POST(form.publisher())
                .build();
        try {
            HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 413) throw ApiException.badRequest("That image is too large for background removal");
            if (res.statusCode() != 200) {
                throw new ApiException(ErrorCode.STORAGE_ERROR, "Background removal failed (HTTP " + res.statusCode() + ")");
            }
            return res.body();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_ERROR, "The background removal service is not reachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Interrupted");
        }
    }
}
