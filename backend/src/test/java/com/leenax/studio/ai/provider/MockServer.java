package com.leenax.studio.ai.provider;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Tiny in-process HTTP server that plays a provider API in tests. */
final class MockServer implements AutoCloseable {

    record Recorded(String method, String pathAndQuery, Map<String, List<String>> headers, String body) {
        String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
            }
            return null;
        }
    }

    record Reply(int status, String body, String contentType) {
        static Reply json(int status, String body) { return new Reply(status, body, "application/json"); }
        static Reply bytes(String body) { return new Reply(200, body, "video/mp4"); }
    }

    private final HttpServer server;
    private final Map<String, Function<Recorded, Reply>> routes = new ConcurrentHashMap<>();
    final List<Recorded> requests = new ArrayList<>();

    MockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String pq = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() != null ? "?" + exchange.getRequestURI().getRawQuery() : "");
            Recorded rec = new Recorded(exchange.getRequestMethod(), pq, exchange.getRequestHeaders(), body);
            synchronized (requests) {
                requests.add(rec);
            }
            Function<Recorded, Reply> handler = routes.get(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            Reply reply = handler != null ? handler.apply(rec) : Reply.json(404, "{\"message\":\"no route\"}");
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", reply.contentType());
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void on(String methodAndPath, Function<Recorded, Reply> handler) {
        routes.put(methodAndPath, handler);
    }

    Recorded last(String method, String pathPrefix) {
        synchronized (requests) {
            for (int i = requests.size() - 1; i >= 0; i--) {
                Recorded r = requests.get(i);
                if (r.method().equals(method) && r.pathAndQuery().startsWith(pathPrefix)) return r;
            }
        }
        throw new AssertionError("No " + method + " request to " + pathPrefix);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
