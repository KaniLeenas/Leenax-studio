package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Luma Dream Machine API (https://docs.lumalabs.ai/reference).
 *   POST /dream-machine/v1/generations/video { prompt, model, aspect_ratio, resolution, duration:"5s", keyframes{frame0{type:image,url}} }
 *   GET  /dream-machine/v1/generations/{id} → state queued|dreaming|completed|failed, assets.video, failure_reason
 *   DELETE /dream-machine/v1/generations/{id}
 *   GET  /dream-machine/v1/credits → credit_balance (US cents)
 * Image-to-video needs an image URL Luma can download, so the studio must be reachable online.
 */
public class LumaAdapter implements VideoProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final ProviderHttp http;
    private final String baseUrl;

    public LumaAdapter(ProviderHttp http) {
        this(http, "https://api.lumalabs.ai");
    }

    public LumaAdapter(ProviderHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.LUMA;
    }

    private HttpRequest.Builder req(String path, ProviderCredentials c) {
        return ProviderHttp.request(baseUrl + "/dream-machine/v1" + path, TIMEOUT)
                .header("Authorization", "Bearer " + c.apiKey());
    }

    @Override
    public void verify(ProviderCredentials credentials) {
        http.send(req("/credits", credentials).GET().build(), code());
    }

    @Override
    public List<BalanceSnapshot> balances(ProviderCredentials credentials) {
        JsonNode res = http.send(req("/credits", credentials).GET().build(), code());
        JsonNode cents = res.get("credit_balance");
        if (cents == null || !cents.isNumber()) return List.of();
        BigDecimal usd = cents.decimalValue().divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return List.of(new BalanceSnapshot("Luma API credit", false, usd, "USD", null));
    }

    @Override
    public VideoResult submit(VideoRequest r, ProviderCredentials credentials) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", RunwayAdapter.withNegative(r.prompt(), r.negativePrompt()));
        body.put("model", r.modelCode());
        if (r.aspectRatio() != null) body.put("aspect_ratio", r.aspectRatio());
        if (r.resolution() != null) body.put("resolution", r.resolution());
        if (r.durationSeconds() != null) body.put("duration", r.durationSeconds() + "s");
        if (r.mode() == VideoRequest.Mode.IMAGE_TO_VIDEO) {
            if (r.image() == null || !isPublic(r.image().publicUrl())) {
                throw new ProviderException(ProviderErrorKind.UNSUPPORTED_CAPABILITY,
                        "Luma downloads the start image from a link, so image-to-video only works when your studio is online with a public media address.");
            }
            body.put("keyframes", Map.of("frame0", Map.of("type", "image", "url", r.image().publicUrl())));
        }
        JsonNode res = http.send(req("/generations/video", credentials)
                .header("Content-Type", "application/json").POST(http.json(body)).build(), code());
        String id = ProviderHttp.text(res, "/id");
        if (id == null) throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Luma returned no generation id");
        return VideoResult.running(id, 0.0);
    }

    @Override
    public VideoResult poll(String id, ProviderCredentials credentials) {
        JsonNode g = http.send(req("/generations/" + id, credentials).GET().build(), code());
        String state = g.path("state").asText("");
        return switch (state) {
            case "completed" -> {
                String url = ProviderHttp.text(g, "/assets/video");
                yield url == null
                        ? VideoResult.failed(id, ProviderErrorKind.GENERATION_FAILED, "Luma finished but returned no video")
                        : VideoResult.succeeded(id, VideoResult.Output.url(url), null, null, null);
            }
            case "failed" -> {
                String reason = g.path("failure_reason").asText("generation failed");
                yield VideoResult.failed(id, ProviderHttp.classifyBadRequest(reason) == ProviderErrorKind.CONTENT_POLICY_REJECTED
                        ? ProviderErrorKind.CONTENT_POLICY_REJECTED : ProviderErrorKind.GENERATION_FAILED, "Luma: " + reason);
            }
            default -> VideoResult.running(id, "dreaming".equals(state) ? 0.5 : 0.05);
        };
    }

    @Override
    public boolean cancel(String id, ProviderCredentials credentials) {
        http.send(req("/generations/" + id, credentials).DELETE().build(), code());
        return true;
    }

    @Override
    public void download(VideoResult.Output output, ProviderCredentials credentials, Path destination) {
        http.download(output.url(), Map.of(), destination, code());
    }

    /** Localhost and private addresses cannot be fetched by Luma's servers. */
    static boolean isPublic(String url) {
        if (url == null) return false;
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            if (host == null || !"https".equalsIgnoreCase(u.getScheme())) return false;
            host = host.toLowerCase();
            return !(host.equals("localhost") || host.endsWith(".local") || host.startsWith("127.")
                    || host.startsWith("10.") || host.startsWith("192.168.") || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
