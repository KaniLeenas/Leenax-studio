package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runway developer API (https://docs.dev.runwayml.com/api).
 * POST /v1/text_to_video | /v1/image_to_video → { id, estimatedCost.credits }
 * GET  /v1/tasks/{id}  → status PENDING|THROTTLED|RUNNING|SUCCEEDED|FAILED|CANCELLED, output[], cost.credits
 * DELETE /v1/tasks/{id} cancels a running task.
 * GET  /v1/organization → creditBalance
 */
public class RunwayAdapter implements VideoProvider {

    public static final String API_VERSION = "2024-11-06";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ProviderHttp http;
    private final String baseUrl;

    public RunwayAdapter(ProviderHttp http) {
        this(http, "https://api.dev.runwayml.com");
    }

    public RunwayAdapter(ProviderHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.RUNWAY;
    }

    private HttpRequest.Builder req(String path, ProviderCredentials c) {
        return ProviderHttp.request(baseUrl + path, TIMEOUT)
                .header("Authorization", "Bearer " + c.apiKey())
                .header("X-Runway-Version", API_VERSION);
    }

    @Override
    public void verify(ProviderCredentials credentials) {
        http.send(req("/v1/organization", credentials).GET().build(), code());
    }

    @Override
    public List<BalanceSnapshot> balances(ProviderCredentials credentials) {
        JsonNode org = http.send(req("/v1/organization", credentials).GET().build(), code());
        JsonNode balance = org.get("creditBalance");
        if (balance == null || !balance.isNumber()) return List.of();
        return List.of(new BalanceSnapshot("Runway credits", false, balance.decimalValue(), "CREDITS", null));
    }

    @Override
    public VideoResult submit(VideoRequest r, ProviderCredentials credentials) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", r.modelCode());
        body.put("promptText", withNegative(r.prompt(), r.negativePrompt()));
        if (r.aspectRatio() != null) body.put("ratio", r.aspectRatio());
        if (r.durationSeconds() != null) body.put("duration", r.durationSeconds());
        if (r.audio() != null) body.put("audio", r.audio());

        String path;
        if (r.mode() == VideoRequest.Mode.IMAGE_TO_VIDEO) {
            if (r.image() == null) throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, "Choose an image first");
            body.put("promptImage", List.of(Map.of("uri", r.image().dataUri(), "position", "first")));
            path = "/v1/image_to_video";
        } else {
            path = "/v1/text_to_video";
        }

        JsonNode res = http.send(req(path, credentials)
                .header("Content-Type", "application/json")
                .POST(http.json(body)).build(), code());
        String id = ProviderHttp.text(res, "/id");
        if (id == null) throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Runway returned no task id");
        JsonNode est = res.at("/estimatedCost/credits");
        return VideoResult.running(id, 0.0, est.isNumber() ? est.decimalValue() : null, "CREDITS");
    }

    @Override
    public VideoResult poll(String id, ProviderCredentials credentials) {
        JsonNode task = http.send(req("/v1/tasks/" + id, credentials).GET().build(), code());
        String status = ProviderHttp.text(task, "/status");
        JsonNode progress = task.get("progress");
        Double p = progress != null && progress.isNumber() ? progress.asDouble() : null;
        if (status == null) return VideoResult.running(id, p);
        return switch (status) {
            case "SUCCEEDED" -> {
                JsonNode output = task.get("output");
                if (output == null || !output.isArray() || output.isEmpty()) {
                    yield VideoResult.failed(id, ProviderErrorKind.GENERATION_FAILED, "Runway finished but returned no video");
                }
                JsonNode cost = task.at("/cost/credits");
                yield VideoResult.succeeded(id, VideoResult.Output.url(output.get(0).asText()),
                        cost.isNumber() ? cost.decimalValue() : null, "CREDITS", null);
            }
            case "FAILED" -> {
                String code = ProviderHttp.text(task, "/failureCode");
                String reason = ProviderHttp.text(task, "/failure");
                ProviderErrorKind kind = code != null && code.toUpperCase().startsWith("SAFETY")
                        ? ProviderErrorKind.CONTENT_POLICY_REJECTED : ProviderErrorKind.GENERATION_FAILED;
                yield VideoResult.failed(id, kind, "Runway: " + (reason != null ? reason : "generation failed")
                        + (code != null ? " (" + code + ")" : ""));
            }
            case "CANCELLED" -> VideoResult.cancelled(id);
            default -> VideoResult.running(id, p); // PENDING, THROTTLED, RUNNING
        };
    }

    @Override
    public boolean cancel(String id, ProviderCredentials credentials) {
        http.send(req("/v1/tasks/" + id, credentials).DELETE().build(), code());
        return true;
    }

    @Override
    public int pollIntervalSeconds() {
        return 6; // Runway: "should not expect updates more frequent than once every five seconds"
    }

    @Override
    public void download(VideoResult.Output output, ProviderCredentials credentials, Path destination) {
        http.download(output.url(), Map.of(), destination, code());
    }

    static String withNegative(String prompt, String negative) {
        if (negative == null || negative.isBlank()) return prompt;
        return prompt + "\nAvoid: " + negative.strip();
    }

    static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
