package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;

/**
 * Shared HTTP plumbing for adapters: JSON in/out and mapping HTTP failures to
 * {@link ProviderErrorKind}. Error messages are built from the provider's
 * response body only — never from our request, so keys cannot leak.
 */
public final class ProviderHttp {

    public static final long MAX_DOWNLOAD_BYTES = 4L * 1024 * 1024 * 1024; // 4 GB safety cap
    private static final int MAX_MESSAGE = 300;

    private final HttpClient client;
    private final ObjectMapper mapper;

    public ProviderHttp(ObjectMapper mapper) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), mapper);
    }

    public ProviderHttp(HttpClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public static HttpRequest.Builder request(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept", "application/json");
    }

    public HttpRequest.BodyPublisher json(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends the request and returns the parsed JSON body, or throws a normalised ProviderException. */
    public JsonNode send(HttpRequest request, ProviderCode provider) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE,
                    provider.displayName() + " did not respond in time");
        } catch (IOException e) {
            throw new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE,
                    "Could not reach " + provider.displayName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE, "Interrupted");
        }

        int status = response.statusCode();
        JsonNode body = parse(response.body());
        if (status >= 200 && status < 300) {
            return body;
        }
        throw error(provider, status, body);
    }

    /** Binary response (audio, images) plus selected headers. */
    public record Binary(byte[] bytes, String contentType, java.util.Map<String, String> headers) {
        public String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return null;
        }
    }

    public Binary sendForBytes(HttpRequest request, ProviderCode provider) {
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE, provider.displayName() + " did not respond in time");
        } catch (IOException e) {
            throw new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE, "Could not reach " + provider.displayName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE, "Interrupted");
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw error(provider, status, parse(new String(response.body(), StandardCharsets.UTF_8)));
        }
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> {
            if (!v.isEmpty()) headers.put(k, v.get(0));
        });
        String type = response.headers().firstValue("content-type").orElse("application/octet-stream");
        return new Binary(response.body(), type, headers);
    }

    /** Streams a file download to disk. Adds headers (e.g. API key) only when given. */
    public void download(String url, Map<String, String> headers, Path destination, ProviderCode provider) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(15)).GET();
        headers.forEach(builder::header);
        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE,
                    "Could not download the video from " + provider.displayName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE, "Interrupted");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) {
                // drain/close
            } catch (IOException ignored) {
                // nothing to do
            }
            throw new ProviderException(response.statusCode() >= 500
                    ? ProviderErrorKind.PROVIDER_UNAVAILABLE : ProviderErrorKind.GENERATION_FAILED,
                    "Video download failed (HTTP " + response.statusCode() + ")", response.statusCode());
        }
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Generated file is too large");
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE, "Video download was interrupted");
        }
    }

    JsonNode parse(String body) {
        if (body == null || body.isBlank()) return NullNode.getInstance();
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return NullNode.getInstance();
        }
    }

    static ProviderException error(ProviderCode provider, int status, JsonNode body) {
        String detail = providerMessage(body);
        String name = provider.displayName();
        ProviderErrorKind kind = switch (status) {
            case 401, 403 -> ProviderErrorKind.INVALID_CREDENTIALS;
            case 402 -> ProviderErrorKind.INSUFFICIENT_CREDITS;
            case 429 -> ProviderErrorKind.RATE_LIMIT_EXCEEDED;
            case 400, 404, 409, 413, 422 -> classifyBadRequest(detail);
            default -> status >= 500 ? ProviderErrorKind.PROVIDER_UNAVAILABLE : ProviderErrorKind.INVALID_REQUEST;
        };
        String message = switch (kind) {
            case INVALID_CREDENTIALS -> name + " rejected the API key" + suffix(detail);
            case INSUFFICIENT_CREDITS -> name + " says the account has no credits left" + suffix(detail);
            case RATE_LIMIT_EXCEEDED -> name + " rate limit reached; will retry" + suffix(detail);
            case PROVIDER_UNAVAILABLE -> name + " is having problems (HTTP " + status + ")" + suffix(detail);
            case CONTENT_POLICY_REJECTED -> name + " declined the prompt or image" + suffix(detail);
            default -> name + " rejected the request" + suffix(detail);
        };
        return new ProviderException(kind, truncate(message), status);
    }

    /** Some providers report credit or safety problems as 400s; recognise the common wording. */
    static ProviderErrorKind classifyBadRequest(String detail) {
        if (detail == null) return ProviderErrorKind.INVALID_REQUEST;
        String d = detail.toLowerCase();
        if (d.contains("credit") || d.contains("balance") || d.contains("insufficient") || d.contains("quota")) {
            return ProviderErrorKind.INSUFFICIENT_CREDITS;
        }
        if (d.contains("safety") || d.contains("moderation") || d.contains("content policy") || d.contains("nsfw")
                || d.contains("inappropriate") || d.contains("violat")) {
            return ProviderErrorKind.CONTENT_POLICY_REJECTED;
        }
        return ProviderErrorKind.INVALID_REQUEST;
    }

    /** Pulls a human-readable message from the common error body shapes. */
    static String providerMessage(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) return null;
        for (String path : new String[]{"/error/message", "/detail/message", "/message", "/detail", "/error", "/failure", "/msg"}) {
            JsonNode node = body.at(path);
            if (node.isTextual() && !node.asText().isBlank()) return node.asText();
        }
        JsonNode detail = body.get("detail");
        if (detail != null && detail.isArray() && !detail.isEmpty()) {
            JsonNode msg = detail.get(0).get("msg");
            if (msg != null && msg.isTextual()) return msg.asText();
        }
        return null;
    }

    private static String suffix(String detail) {
        return detail == null ? "." : ": " + detail;
    }

    static String truncate(String s) {
        return s.length() <= MAX_MESSAGE ? s : s.substring(0, MAX_MESSAGE - 1) + "…";
    }

    public static String text(JsonNode node, String path) {
        JsonNode n = node.at(path);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}
