package com.leenax.studio.documents;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.provider.Multipart;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Calls the private document service (docker compose service "docservice"). It runs
 * on your own server, reads text and does OCR there: no provider account, no credits,
 * nothing leaves the server.
 */
@Component
public class DocumentReader {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageResult(int page, String text, String method, Double confidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(int pageCount, int pagesRead, boolean truncated, int ocrPages, boolean converted,
                         String language, List<String> moduleCodes, String title, List<PageResult> pages) {
    }

    /** Why a document couldn't be read. Retryable failures are tried again later. */
    public static class ReadFailure extends RuntimeException {
        private final boolean retryable;

        public ReadFailure(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String baseUrl;
    private final HttpClient client;

    public DocumentReader(@Value("${leenax.docservice.url:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean configured() {
        return !baseUrl.isBlank();
    }

    public Result read(byte[] file, String filename, String contentType, boolean forceOcr) {
        Multipart form = new Multipart()
                .file("file", filename, contentType, file)
                .field("force_ocr", Boolean.toString(forceOcr));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/extract"))
                // A long scanned book at 300 dpi takes a while.
                .timeout(Duration.ofMinutes(40))
                .header("Content-Type", form.contentType())
                .POST(form.publisher())
                .build();
        HttpResponse<byte[]> res;
        try {
            res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ReadFailure("The document service is not reachable", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReadFailure("Interrupted", true);
        }
        return parse(res.statusCode(), res.body());
    }

    static Result parse(int status, byte[] body) {
        if (status == 200) {
            try {
                Result r = JSON.readValue(body, Result.class);
                if (r.pages() == null) throw new ReadFailure("The document service sent an incomplete answer", true);
                return r;
            } catch (IOException e) {
                throw new ReadFailure("The document service sent an answer that couldn't be read", true);
            }
        }
        String detail = detail(body);
        if (status == 400 || status == 413 || status == 415 || status == 422) {
            // The file itself is the problem: trying again won't help.
            throw new ReadFailure(detail != null ? detail : "This document can't be read", false);
        }
        throw new ReadFailure("The document service failed (HTTP " + status + ")" + (detail != null ? ": " + detail : ""), true);
    }

    private static String detail(byte[] body) {
        try {
            JsonNode n = JSON.readTree(body);
            JsonNode d = n == null ? null : n.get("detail");
            if (d != null && d.isTextual()) {
                String s = d.asText();
                return s.length() > 300 ? s.substring(0, 300) : s;
            }
        } catch (IOException | RuntimeException e) {
            // not JSON
        }
        return null;
    }
}
