package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Anthropic Claude models, used for prompt enhancement. GET /v1/models, POST /v1/messages. */
public class AnthropicAdapter implements TextProvider {

    static final String VERSION = "2023-06-01";
    private final ProviderHttp http;
    private final String baseUrl;

    public AnthropicAdapter(ProviderHttp http) {
        this(http, "https://api.anthropic.com");
    }

    public AnthropicAdapter(ProviderHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.ANTHROPIC;
    }

    private HttpRequest.Builder req(String path, Duration timeout, ProviderCredentials c) {
        return ProviderHttp.request(baseUrl + path, timeout)
                .header("x-api-key", c.apiKey())
                .header("anthropic-version", VERSION);
    }

    @Override
    public void verify(ProviderCredentials credentials) {
        http.send(req("/v1/models?limit=1", Duration.ofSeconds(20), credentials).GET().build(), code());
    }

    @Override
    public List<String> listModels(ProviderCredentials credentials) {
        JsonNode res = http.send(req("/v1/models?limit=100", Duration.ofSeconds(20), credentials).GET().build(), code());
        List<String> out = new ArrayList<>();
        for (JsonNode m : res.path("data")) {
            String id = m.path("id").asText("");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /** Long enough for full scripts and edit plans. */
    static final int MAX_TOKENS = 8192;

    @Override
    public String complete(String model, String systemPrompt, String userPrompt, ProviderCredentials credentials) {
        return send(model, systemPrompt, userPrompt, credentials);
    }

    @Override
    public String completeWithImages(String model, String systemPrompt, String userPrompt, List<MediaTypes.Sample> images,
                                     ProviderCredentials credentials) {
        List<Object> content = new java.util.ArrayList<>();
        for (MediaTypes.Sample img : images) {
            content.add(Map.of("type", "image", "source", Map.of("type", "base64", "media_type", img.mimeType(),
                    "data", java.util.Base64.getEncoder().encodeToString(img.bytes()))));
        }
        content.add(Map.of("type", "text", "text", userPrompt));
        return send(model, systemPrompt, content, credentials);
    }

    private String send(String model, String systemPrompt, Object userContent, ProviderCredentials credentials) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", MAX_TOKENS,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userContent)));
        JsonNode res = http.send(req("/v1/messages", Duration.ofSeconds(180), credentials)
                .header("Content-Type", "application/json").POST(http.json(body)).build(), code());
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : res.path("content")) {
            if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText());
        }
        if (sb.isEmpty()) throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Claude returned no text");
        return sb.toString().strip();
    }
}
