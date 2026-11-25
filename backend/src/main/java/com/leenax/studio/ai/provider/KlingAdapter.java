package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kling AI direct API, "new" API-key standard with the model in the path
 * (https://kling.ai/document-api).
 *   POST /text-to-video/{model}   { prompt, settings{resolution, aspect_ratio, duration}, options{external_task_id} }
 *   POST /image-to-video/{model}  { prompt, contents[{type:first_frame, url}], settings{resolution, duration} }
 *   GET  /tasks?task_ids={id}     → data[0].status submitted|processing|succeeded|failed, outputs[{type:video,url}]
 *   GET  /account/costs           → data.resource_pack_subscribe_infos[] (remaining_quantity; 12-hour delay)
 * Responses wrap everything in { code, message, data }; code 0 means success.
 */
public class KlingAdapter implements VideoProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final ProviderHttp http;
    private final String baseUrl;

    public KlingAdapter(ProviderHttp http) {
        this(http, "https://api-singapore.klingai.com");
    }

    public KlingAdapter(ProviderHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.KLING;
    }

    private HttpRequest.Builder req(String path, ProviderCredentials c) {
        return ProviderHttp.request(baseUrl + path, TIMEOUT)
                .header("Authorization", "Bearer " + c.apiKey())
                .header("Content-Type", "application/json");
    }

    /** Kling can return HTTP 200 with a non-zero business code. */
    private JsonNode call(HttpRequest request) {
        JsonNode res = http.send(request, code());
        JsonNode codeNode = res.get("code");
        if (codeNode != null && codeNode.isNumber() && codeNode.asInt() != 0) {
            String msg = ProviderHttp.text(res, "/message");
            ProviderErrorKind kind = ProviderHttp.classifyBadRequest(msg);
            throw new ProviderException(kind, ProviderHttp.truncate("Kling AI: " + (msg != null ? msg : "request failed")
                    + " (code " + codeNode.asInt() + ")"));
        }
        return res;
    }

    @Override
    public void verify(ProviderCredentials credentials) {
        balances(credentials);
    }

    @Override
    public List<BalanceSnapshot> balances(ProviderCredentials credentials) {
        long end = System.currentTimeMillis();
        long start = end - Duration.ofDays(365).toMillis();
        JsonNode res = call(req("/account/costs?start_time=" + start + "&end_time=" + end, credentials).GET().build());
        List<BalanceSnapshot> out = new ArrayList<>();
        for (JsonNode pack : res.at("/data/resource_pack_subscribe_infos")) {
            String status = pack.path("status").asText("");
            if (!"online".equals(status)) continue; // skip expired, used-up and not-yet-active packs
            BigDecimal remaining = pack.path("remaining_quantity").decimalValue();
            String name = pack.path("resource_pack_name").asText("Kling resource pack");
            JsonNode invalid = pack.get("invalid_time");
            Instant expires = invalid != null && invalid.isNumber() && invalid.asLong() > 0
                    ? Instant.ofEpochMilli(invalid.asLong()) : null;
            boolean free = name.toLowerCase().contains("free") || name.toLowerCase().contains("trial");
            out.add(new BalanceSnapshot(name, free, remaining, "UNITS", expires));
        }
        return out;
    }

    @Override
    public VideoResult submit(VideoRequest r, ProviderCredentials credentials) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (r.resolution() != null) settings.put("resolution", r.resolution());
        if (r.durationSeconds() != null) settings.put("duration", r.durationSeconds());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", RunwayAdapter.withNegative(r.prompt(), r.negativePrompt()));
        String path;
        if (r.mode() == VideoRequest.Mode.IMAGE_TO_VIDEO) {
            if (r.image() == null) throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, "Choose an image first");
            // Kling accepts a URL or raw Base64 (no data: prefix) in "url".
            body.put("contents", List.of(Map.of("type", "first_frame", "url", r.image().base64())));
            path = "/image-to-video/" + r.modelCode();
        } else {
            if (r.aspectRatio() != null) settings.put("aspect_ratio", r.aspectRatio());
            path = "/text-to-video/" + r.modelCode();
        }
        body.put("settings", settings);
        if (r.clientReference() != null) body.put("options", Map.of("external_task_id", r.clientReference()));

        JsonNode res = call(req(path, credentials).POST(http.json(body)).build());
        String id = ProviderHttp.text(res, "/data/id");
        if (id == null) throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Kling AI returned no task id");
        return VideoResult.running(id, 0.0);
    }

    @Override
    public VideoResult poll(String id, ProviderCredentials credentials) {
        JsonNode res = call(req("/tasks?task_ids=" + URLEncoder.encode(id, StandardCharsets.UTF_8), credentials).GET().build());
        JsonNode task = null;
        for (JsonNode t : res.path("data")) {
            if (id.equals(t.path("id").asText())) task = t;
        }
        if (task == null) return VideoResult.running(id, null);
        String status = task.path("status").asText("");
        return switch (status) {
            case "succeeded" -> {
                String url = null;
                Double seconds = null;
                for (JsonNode o : task.path("outputs")) {
                    if ("video".equals(o.path("type").asText()) && o.hasNonNull("url")) {
                        url = o.get("url").asText();
                        if (o.get("duration") != null && o.get("duration").isNumber()) seconds = o.get("duration").asDouble();
                        break;
                    }
                }
                JsonNode billed = task.at("/usage/output_duration");
                if (billed.isNumber()) seconds = billed.asDouble();
                yield url == null
                        ? VideoResult.failed(id, ProviderErrorKind.GENERATION_FAILED, "Kling AI finished but returned no video")
                        : VideoResult.succeeded(id, VideoResult.Output.url(url), null, null, seconds);
            }
            case "failed" -> {
                String msg = task.path("message").asText("generation failed");
                yield VideoResult.failed(id, ProviderHttp.classifyBadRequest(msg) == ProviderErrorKind.CONTENT_POLICY_REJECTED
                        ? ProviderErrorKind.CONTENT_POLICY_REJECTED : ProviderErrorKind.GENERATION_FAILED, "Kling AI: " + msg);
            }
            default -> VideoResult.running(id, "processing".equals(status) ? 0.5 : 0.05);
        };
    }

    @Override
    public int pollIntervalSeconds() {
        return 8;
    }

    @Override
    public void download(VideoResult.Output output, ProviderCredentials credentials, Path destination) {
        http.download(output.url(), Map.of(), destination, code());
    }
}
