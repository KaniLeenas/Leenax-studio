package com.leenax.studio.social;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** In-process HTTP server that plays the platforms' APIs in tests (status, headers and body). */
public final class PlatformMock implements AutoCloseable {

    public record Req(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
            }
            return null;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** Form fields or query parameters, decoded. */
        public Map<String, String> form() {
            return decode(text());
        }

        public Map<String, String> params() {
            return decode(query == null ? "" : query);
        }

        static Map<String, String> decode(String s) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String part : s.split("&")) {
                if (part.isEmpty()) continue;
                int i = part.indexOf('=');
                String k = java.net.URLDecoder.decode(i < 0 ? part : part.substring(0, i), StandardCharsets.UTF_8);
                String v = i < 0 ? "" : java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
                m.put(k, v);
            }
            return m;
        }
    }

    public record Reply(int status, String body, Map<String, String> headers) {
        public static Reply json(int status, String body) {
            return new Reply(status, body, Map.of());
        }

        public Reply with(String header, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(header, value);
            return new Reply(status, body, h);
        }
    }

    private final HttpServer server;
    private final Map<String, Function<Req, Reply>> routes = new ConcurrentHashMap<>();
    public final List<Req> requests = new ArrayList<>();

    public PlatformMock() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            Req req = new Req(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                    ex.getRequestHeaders(), body);
            synchronized (requests) {
                requests.add(req);
            }
            Function<Req, Reply> h = routes.get(req.method() + " " + req.path());
            Reply r = h != null ? h.apply(req) : Reply.json(404, "{\"error\":{\"message\":\"no route " + req.path() + "\"}}");
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            }
            ex.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void on(String methodAndPath, Function<Req, Reply> handler) {
        routes.put(methodAndPath, handler);
    }

    public List<Req> all(String method, String path) {
        synchronized (requests) {
            return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
        }
    }

    public Req last(String method, String path) {
        List<Req> l = all(method, path);
        if (l.isEmpty()) throw new AssertionError("No " + method + " " + path);
        return l.get(l.size() - 1);
    }

    public static SocialProperties props(String base) {
        return new SocialProperties("https://studio.test",
                new SocialProperties.Google("g-client", "g-secret"),
                new SocialProperties.Meta("m-app", "m-secret", "v26.0"),
                new SocialProperties.TikTok("t-key", "t-secret"),
                SocialProperties.Endpoints.at(base));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
