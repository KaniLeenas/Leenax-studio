package com.leenax.studio.social;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Plain HTTP for the platform clients. Unlike the AI provider helper it never
 * follows redirects (YouTube answers resumable uploads with 308) and hands the
 * caller the status, headers and body, because each platform reports errors
 * in its own way. Nothing here logs requests.
 */
public class SocialHttp {

    private final HttpClient client;
    private final ObjectMapper mapper;

    public SocialHttp(ObjectMapper mapper) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), mapper);
    }

    public SocialHttp(HttpClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public record Response(int status, Map<String, List<String>> headers, String body, JsonNode json) {
        public boolean ok() { return status >= 200 && status < 300; }

        public String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }

        public String text(String pointer) {
            JsonNode n = json.at(pointer);
            return n.isMissingNode() || n.isNull() ? null : n.asText();
        }
    }

    public static HttpRequest.Builder request(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
    }

    /** Sends a request. Network failures become TEMPORARY errors; HTTP errors are returned for the caller to classify. */
    public Response send(HttpRequest request, String platformName) {
        HttpResponse<String> r;
        try {
            r = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new SocialException(SocialException.Kind.TEMPORARY, platformName + " did not respond in time");
        } catch (IOException e) {
            throw new SocialException(SocialException.Kind.TEMPORARY, "Could not reach " + platformName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SocialException(SocialException.Kind.TEMPORARY, "Interrupted");
        }
        return new Response(r.statusCode(), r.headers().map(), r.body(), parse(r.body()));
    }

    public JsonNode parse(String body) {
        if (body == null || body.isBlank()) return NullNode.getInstance();
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return NullNode.getInstance();
        }
    }

    public HttpRequest.BodyPublisher json(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static HttpRequest.BodyPublisher form(Map<String, String> fields) {
        return HttpRequest.BodyPublishers.ofString(encode(fields), StandardCharsets.UTF_8);
    }

    public static String encode(Map<String, String> fields) {
        StringJoiner j = new StringJoiner("&");
        fields.forEach((k, v) -> {
            if (v != null) j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return j.toString();
    }

    public static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Streams {@code length} bytes of a file starting at {@code offset}, without loading it into memory. */
    public static HttpRequest.BodyPublisher filePart(Path file, long offset, long length) {
        if (length <= 0) return HttpRequest.BodyPublishers.noBody();
        return HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
                channel.position(offset);
                return new LimitedStream(Channels.newInputStream(channel), length);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read the video file", e);
            }
        }), length);
    }

    private static final class LimitedStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedStream(InputStream in, long remaining) {
            this.in = in;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(buf, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Trims platform messages for display. */
    public static String clip(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= 300 ? t : t.substring(0, 299) + "…";
    }

    /** "Retry-After" in seconds, if the platform sent one. */
    public static Duration retryAfter(Response r, Duration fallback) {
        String h = r.header("retry-after");
        if (h != null) {
            try {
                long s = Long.parseLong(h.strip());
                if (s > 0 && s < 86_400) return Duration.ofSeconds(s);
            } catch (NumberFormatException ignored) {
                // HTTP-date form: use the fallback
            }
        }
        return fallback;
    }
}
