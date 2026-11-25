package com.leenax.studio.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** HTTP client for the private render service (renderer/server.mjs). */
public class RendererClient {

    public record Status(String status, double progress, String error) {
        public boolean finished() {
            return "done".equals(status) || "failed".equals(status) || "cancelled".equals(status);
        }
    }

    /** The renderer could not be reached or answered with an unexpected error: try again later. */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
    }

    /** The renderer does not know this render (e.g. it restarted). */
    public static class UnknownRenderException extends RuntimeException {
        public UnknownRenderException() {
            super("Unknown render");
        }
    }

    private final String baseUrl;
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public RendererClient(String baseUrl, String token, ObjectMapper mapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean configured() {
        return !baseUrl.isBlank();
    }

    public void submit(String id, Map<String, Object> props, Map<String, Object> params) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(params);
        body.put("id", id);
        body.put("props", props);
        try {
            HttpResponse<String> res = http.send(request("/renders").timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 400) throw new IllegalArgumentException(error(res.body()));
            if (res.statusCode() != 202) throw new UnavailableException("Renderer answered HTTP " + res.statusCode());
        } catch (IOException e) {
            throw new UnavailableException("The render service is not reachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("Interrupted");
        }
    }

    public Status status(String id) {
        try {
            HttpResponse<String> res = http.send(request("/renders/" + id).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) throw new UnknownRenderException();
            if (res.statusCode() != 200) throw new UnavailableException("Renderer answered HTTP " + res.statusCode());
            JsonNode j = mapper.readTree(res.body());
            return new Status(j.path("status").asText(), j.path("progress").asDouble(0), j.path("error").asText(null));
        } catch (IOException e) {
            throw new UnavailableException("The render service is not reachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("Interrupted");
        }
    }

    public void download(String id, Path target) {
        try {
            HttpResponse<Path> res = http.send(request("/renders/" + id + "/file").timeout(Duration.ofMinutes(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(target));
            if (res.statusCode() == 404) throw new UnknownRenderException();
            if (res.statusCode() != 200) throw new UnavailableException("Renderer answered HTTP " + res.statusCode());
        } catch (IOException e) {
            throw new UnavailableException("Could not download the finished video");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("Interrupted");
        }
    }

    public void cancel(String id) {
        try {
            http.send(request("/renders/" + id).timeout(Duration.ofSeconds(15)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // best effort: the renderer drops old renders on its own
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (!token.isBlank()) b.header("X-Renderer-Token", token);
        return b;
    }

    private String error(String body) {
        try {
            return mapper.readTree(body).path("error").asText("The renderer refused the timeline");
        } catch (IOException e) {
            return "The renderer refused the timeline";
        }
    }
}
