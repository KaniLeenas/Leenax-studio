package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI.
 *   Text: GET /v1/models, POST /v1/chat/completions (prompt writing, translation).
 *   Images: POST /v1/images/generations {model, prompt, size WxH, n, quality, background, output_format} → data[].b64_json;
 *           POST /v1/images/edits (multipart, image[] files) when reference images are given.
 *   Transcription: POST /v1/audio/transcriptions whisper-1, response_format=verbose_json, word + segment timestamps.
 */
public class OpenAiAdapter implements TextProvider, ImageProvider, TranscriptionProvider {

    private final ProviderHttp http;
    private final String baseUrl;

    public OpenAiAdapter(ProviderHttp http) {
        this(http, "https://api.openai.com");
    }

    public OpenAiAdapter(ProviderHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.OPENAI;
    }

    private HttpRequest.Builder req(String path, Duration timeout, ProviderCredentials c) {
        return ProviderHttp.request(baseUrl + path, timeout).header("Authorization", "Bearer " + c.apiKey());
    }

    @Override
    public void verify(ProviderCredentials credentials) {
        http.send(req("/v1/models", Duration.ofSeconds(20), credentials).GET().build(), code());
    }

    @Override
    public List<String> listModels(ProviderCredentials credentials) {
        JsonNode res = http.send(req("/v1/models", Duration.ofSeconds(20), credentials).GET().build(), code());
        List<String> out = new ArrayList<>();
        for (JsonNode m : res.path("data")) {
            String id = m.path("id").asText("");
            // Keep chat-capable families; skip embeddings, audio, image and moderation models.
            if (id.isEmpty() || id.contains("embedding") || id.contains("whisper") || id.contains("tts")
                    || id.contains("dall-e") || id.contains("moderation") || id.contains("image") || id.contains("audio")
                    || id.contains("realtime") || id.contains("transcribe") || id.contains("search")) continue;
            out.add(id);
        }
        out.sort(null);
        return out;
    }

    // ---- images ------------------------------------------------------------

    @Override
    public List<MediaTypes.Clip> generate(MediaTypes.ImageRequest r, ProviderCredentials credentials) {
        int n = Math.min(Math.max(r.count(), 1), 10);
        JsonNode res;
        if (r.references() == null || r.references().isEmpty()) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", r.model());
            body.put("prompt", r.prompt());
            body.put("n", n);
            if (r.size() != null) body.put("size", r.size());
            if (r.quality() != null) body.put("quality", r.quality());
            body.put("background", r.transparent() ? "transparent" : "auto");
            body.put("output_format", "png");
            res = http.send(req("/v1/images/generations", Duration.ofMinutes(6), credentials)
                    .header("Content-Type", "application/json").POST(http.json(body)).build(), code());
        } else {
            Multipart form = new Multipart().field("model", r.model()).field("prompt", r.prompt())
                    .field("n", String.valueOf(n)).field("size", r.size()).field("quality", r.quality())
                    .field("background", r.transparent() ? "transparent" : "auto").field("output_format", "png");
            for (MediaTypes.Sample ref : r.references()) form.file("image[]", ref.filename(), ref.mimeType(), ref.bytes());
            res = http.send(req("/v1/images/edits", Duration.ofMinutes(6), credentials)
                    .header("Content-Type", form.contentType()).POST(form.publisher()).build(), code());
        }
        List<MediaTypes.Clip> out = new ArrayList<>();
        for (JsonNode d : res.path("data")) {
            if (d.hasNonNull("b64_json")) {
                out.add(new MediaTypes.Clip(java.util.Base64.getDecoder().decode(d.get("b64_json").asText()), "image/png", null));
            }
        }
        if (out.isEmpty()) throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "OpenAI returned no image");
        return out;
    }

    // ---- transcription -------------------------------------------------------

    @Override
    public long maxBytes() {
        return 25L * 1024 * 1024; // OpenAI's upload limit for transcription
    }

    @Override
    public MediaTypes.Transcript transcribe(java.nio.file.Path audio, String mimeType, String languageCode,
                                            ProviderCredentials credentials) {
        byte[] data;
        try {
            data = java.nio.file.Files.readAllBytes(audio);
        } catch (java.io.IOException e) {
            throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Could not read the audio file");
        }
        Multipart form = new Multipart().field("model", "whisper-1").field("response_format", "verbose_json")
                .field("timestamp_granularities[]", "word").field("timestamp_granularities[]", "segment")
                .field("language", languageCode)
                .file("file", audio.getFileName().toString(), mimeType, data);
        JsonNode res = http.send(req("/v1/audio/transcriptions", Duration.ofMinutes(15), credentials)
                .header("Content-Type", form.contentType()).POST(form.publisher()).build(), code());
        List<MediaTypes.Word> words = new ArrayList<>();
        for (JsonNode w : res.path("words")) {
            String t = w.path("word").asText("").strip();
            if (!t.isEmpty()) words.add(new MediaTypes.Word(t, w.path("start").asDouble(), w.path("end").asDouble()));
        }
        List<MediaTypes.Segment> segments = new ArrayList<>();
        for (JsonNode sg : res.path("segments")) {
            segments.add(new MediaTypes.Segment(sg.path("start").asDouble(), sg.path("end").asDouble(), sg.path("text").asText("").strip()));
        }
        return new MediaTypes.Transcript(res.path("language").asText(languageCode), res.path("text").asText(""), words, segments);
    }

    @Override
    public String complete(String model, String systemPrompt, String userPrompt, ProviderCredentials credentials) {
        return chat(model, systemPrompt, userPrompt, credentials);
    }

    @Override
    public String completeWithImages(String model, String systemPrompt, String userPrompt, List<MediaTypes.Sample> images,
                                     ProviderCredentials credentials) {
        List<Object> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", userPrompt));
        for (MediaTypes.Sample img : images) {
            content.add(Map.of("type", "image_url", "image_url", Map.of(
                    "url", "data:" + img.mimeType() + ";base64," + java.util.Base64.getEncoder().encodeToString(img.bytes()),
                    "detail", "low")));
        }
        return chat(model, systemPrompt, content, credentials);
    }

    private String chat(String model, String systemPrompt, Object userContent, ProviderCredentials credentials) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)));
        JsonNode res = http.send(req("/v1/chat/completions", Duration.ofSeconds(180), credentials)
                .header("Content-Type", "application/json").POST(http.json(body)).build(), code());
        String text = ProviderHttp.text(res, "/choices/0/message/content");
        if (text == null || text.isBlank()) throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "OpenAI returned no text");
        return text.strip();
    }
}
