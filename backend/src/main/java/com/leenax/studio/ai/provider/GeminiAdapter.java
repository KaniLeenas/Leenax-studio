package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini API.
 *
 * Video — Gemini Omni Flash via the Interactions API
 * (https://ai.google.dev/gemini-api/docs/omni):
 *   POST /v1beta/interactions  { model, input[], response_format{type:video, aspect_ratio, resolution, delivery:"uri"} }
 *   The call is synchronous. With URI delivery the video lands in the Files API; we then poll
 *   GET /v1beta/files/{id} until state ACTIVE and download /v1beta/files/{id}:download?alt=media.
 *
 * Text — generateContent, used for prompt enhancement.
 *
 * Speech — Gemini TTS via the Interactions API: input[{type:user_input, content[{type:text, text,
 *   annotations[{type:speech_metadata, style}]}]}], response_format{type:audio}, generation_config.speech_config[{voice}].
 *   Returns 24 kHz mono WAV in steps[model_output].content[type=audio].data.
 *
 * Images — Nano Banana via the Interactions API (header Api-Revision: 2026-05-20):
 *   response_format{type:image, mime_type, aspect_ratio, image_size}; reference images as {type:image, mime_type, data}.
 */
public class GeminiAdapter implements VideoProvider, TextProvider, SpeechProvider, ImageProvider {

    private static final String FILE_PREFIX = "file:";
    private final ProviderHttp http;
    private final String baseUrl;

    public GeminiAdapter(ProviderHttp http) {
        this(http, "https://generativelanguage.googleapis.com");
    }

    public GeminiAdapter(ProviderHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.GOOGLE_GEMINI;
    }

    private HttpRequest.Builder req(String path, Duration timeout, ProviderCredentials c) {
        return ProviderHttp.request(baseUrl + path, timeout).header("x-goog-api-key", c.apiKey());
    }

    // ---- verify / text ---------------------------------------------------

    @Override
    public void verify(ProviderCredentials credentials) {
        http.send(req("/v1beta/models?pageSize=1", Duration.ofSeconds(20), credentials).GET().build(), code());
    }

    @Override
    public List<String> listModels(ProviderCredentials credentials) {
        JsonNode res = http.send(req("/v1beta/models?pageSize=1000", Duration.ofSeconds(20), credentials).GET().build(), code());
        List<String> out = new ArrayList<>();
        for (JsonNode m : res.path("models")) {
            boolean generates = false;
            for (JsonNode method : m.path("supportedGenerationMethods")) {
                if ("generateContent".equals(method.asText())) generates = true;
            }
            String name = m.path("name").asText("");
            if (generates && name.startsWith("models/")) out.add(name.substring("models/".length()));
        }
        return out;
    }

    @Override
    public String complete(String model, String systemPrompt, String userPrompt, ProviderCredentials credentials) {
        return generate(model, systemPrompt, List.of(Map.of("text", userPrompt)), credentials);
    }

    @Override
    public String completeWithImages(String model, String systemPrompt, String userPrompt, List<MediaTypes.Sample> images,
                                     ProviderCredentials credentials) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (MediaTypes.Sample img : images) {
            parts.add(Map.of("inline_data", Map.of("mime_type", img.mimeType(),
                    "data", java.util.Base64.getEncoder().encodeToString(img.bytes()))));
        }
        parts.add(Map.of("text", userPrompt));
        return generate(model, systemPrompt, parts, credentials);
    }

    private String generate(String model, String systemPrompt, List<? extends Map<String, Object>> parts, ProviderCredentials credentials) {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of("role", "user", "parts", parts)));
        JsonNode res = http.send(req("/v1beta/models/" + model + ":generateContent", Duration.ofSeconds(180), credentials)
                .header("Content-Type", "application/json").POST(http.json(body)).build(), code());
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : res.at("/candidates/0/content/parts")) {
            if (part.has("text")) sb.append(part.get("text").asText());
        }
        if (sb.isEmpty()) throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Gemini returned no text");
        return sb.toString().strip();
    }

    // ---- video -----------------------------------------------------------

    @Override
    public VideoResult submit(VideoRequest r, ProviderCredentials credentials) {
        List<Map<String, Object>> input = new ArrayList<>();
        input.add(Map.of("type", "text", "text", buildPrompt(r)));
        if (r.mode() == VideoRequest.Mode.IMAGE_TO_VIDEO) {
            if (r.image() == null) throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, "Choose an image first");
            input.add(Map.of("type", "image", "data", r.image().base64(), "mime_type", r.image().mimeType()));
        }
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "video");
        if (r.aspectRatio() != null) format.put("aspect_ratio", r.aspectRatio());
        if (r.resolution() != null) format.put("resolution", r.resolution());
        format.put("delivery", "uri"); // recommended for videos over 4 MB

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", r.modelCode());
        body.put("input", input);
        body.put("response_format", format);
        body.put("generation_config", Map.of("video_config", Map.of("task",
                r.mode() == VideoRequest.Mode.IMAGE_TO_VIDEO ? "image_to_video" : "text_to_video")));
        body.put("background", false);
        body.put("store", false);
        body.put("stream", false);

        // Synchronous generation: allow several minutes.
        JsonNode res = http.send(req("/v1beta/interactions", Duration.ofMinutes(12), credentials)
                .header("Content-Type", "application/json").POST(http.json(body)).build(), code());

        JsonNode video = findVideo(res);
        String interactionId = ProviderHttp.text(res, "/id");
        if (video == null) {
            String status = ProviderHttp.text(res, "/status");
            return VideoResult.failed(interactionId, ProviderErrorKind.GENERATION_FAILED,
                    "Gemini returned no video" + (status != null ? " (status " + status + ")" : ""));
        }
        if (video.hasNonNull("data")) {
            byte[] bytes = java.util.Base64.getDecoder().decode(video.get("data").asText());
            return VideoResult.succeeded(interactionId, VideoResult.Output.inline(bytes), null, null, null);
        }
        String uri = video.path("uri").asText(null);
        String fileId = fileId(uri);
        if (fileId == null) {
            return VideoResult.failed(interactionId, ProviderErrorKind.GENERATION_FAILED, "Gemini returned an unreadable video reference");
        }
        // Hand over to poll(): the file may still be processing.
        return VideoResult.running(FILE_PREFIX + fileId, 0.9);
    }

    @Override
    public VideoResult poll(String providerJobId, ProviderCredentials credentials) {
        if (!providerJobId.startsWith(FILE_PREFIX)) {
            return VideoResult.failed(providerJobId, ProviderErrorKind.GENERATION_FAILED, "Unknown Gemini job reference");
        }
        String fileId = providerJobId.substring(FILE_PREFIX.length());
        JsonNode file = http.send(req("/v1beta/files/" + fileId, Duration.ofSeconds(20), credentials).GET().build(), code());
        String state = ProviderHttp.text(file, "/state");
        if ("ACTIVE".equals(state)) {
            String url = baseUrl + "/v1beta/files/" + fileId + ":download?alt=media";
            return VideoResult.succeeded(providerJobId, VideoResult.Output.authenticatedUrl(url), null, null, null);
        }
        if ("FAILED".equals(state)) {
            return VideoResult.failed(providerJobId, ProviderErrorKind.GENERATION_FAILED, "Gemini could not finish the video file");
        }
        return VideoResult.running(providerJobId, 0.95);
    }

    @Override
    public int pollIntervalSeconds() {
        return 4;
    }

    @Override
    public void download(VideoResult.Output output, ProviderCredentials credentials, Path destination) {
        if (output.inlineBytes() != null) {
            try {
                java.nio.file.Files.write(destination, output.inlineBytes());
            } catch (java.io.IOException e) {
                throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Could not save the video");
            }
            return;
        }
        http.download(output.url(), output.needsAuth() ? Map.of("x-goog-api-key", credentials.apiKey()) : Map.of(),
                destination, code());
    }

    // ---- speech ------------------------------------------------------------

    /** The 30 prebuilt Gemini TTS voices. All of them speak every supported language, including Tamil. */
    static final List<String> PREBUILT_VOICES = List.of("Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus",
            "Aoede", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome",
            "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird",
            "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat");

    public static final String DEFAULT_TTS_MODEL = "gemini-3.8-flash-tts";
    public static final String IMAGE_API_REVISION = "2026-05-20";

    @Override
    public List<MediaTypes.VoiceInfo> voices(ProviderCredentials credentials) {
        List<MediaTypes.VoiceInfo> out = new ArrayList<>();
        for (String v : PREBUILT_VOICES) out.add(new MediaTypes.VoiceInfo(v, v, "premade", null, null, null, null, null, false));
        return out;
    }

    @Override
    public MediaTypes.Clip synthesize(MediaTypes.SpeechRequest r, ProviderCredentials credentials) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", r.text());
        if (r.style() != null && !r.style().isBlank()) {
            textPart.put("annotations", List.of(Map.of("type", "speech_metadata", "style", r.style())));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", r.model() == null || r.model().isBlank() ? DEFAULT_TTS_MODEL : r.model());
        body.put("input", List.of(Map.of("type", "user_input", "content", List.of(textPart))));
        body.put("response_format", Map.of("type", "audio"));
        body.put("generation_config", Map.of("speech_config", List.of(Map.of("voice", r.voiceId()))));
        JsonNode res = http.send(req("/v1beta/interactions", Duration.ofMinutes(5), credentials)
                .header("Content-Type", "application/json").POST(http.json(body)).build(), code());
        List<JsonNode> audio = findAll(res, "audio");
        if (audio.isEmpty() || !audio.get(audio.size() - 1).hasNonNull("data")) {
            throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Gemini returned no audio");
        }
        byte[] bytes = java.util.Base64.getDecoder().decode(audio.get(audio.size() - 1).get("data").asText());
        return new MediaTypes.Clip(bytes, "audio/wav", null);
    }

    @Override
    public int maxChars(String model) {
        return 3500;
    }

    /** Gemini TTS takes momentary sounds and pauses inline in angle brackets, e.g. "<short pause>". */
    @Override
    public String pauseMarkup(double seconds) {
        int n = (int) Math.max(1, Math.min(6, Math.round(seconds / 0.5)));
        return String.join(" ", java.util.Collections.nCopies(n, "<short pause>"));
    }

    // ---- images ------------------------------------------------------------

    @Override
    public List<MediaTypes.Clip> generate(MediaTypes.ImageRequest r, ProviderCredentials credentials) {
        List<Map<String, Object>> input = new ArrayList<>();
        input.add(Map.of("type", "text", "text", r.prompt()));
        if (r.references() != null) {
            for (MediaTypes.Sample ref : r.references()) {
                input.add(Map.of("type", "image", "mime_type", ref.mimeType(),
                        "data", java.util.Base64.getEncoder().encodeToString(ref.bytes())));
            }
        }
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "image");
        format.put("mime_type", "image/png");
        if (r.aspectRatio() != null) format.put("aspect_ratio", r.aspectRatio());
        if (r.imageSize() != null) format.put("image_size", r.imageSize());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", r.model());
        body.put("input", input);
        body.put("response_format", format);

        List<MediaTypes.Clip> out = new ArrayList<>();
        for (int i = 0; i < Math.max(1, r.count()); i++) {
            JsonNode res = http.send(req("/v1beta/interactions", Duration.ofMinutes(5), credentials)
                    .header("Api-Revision", IMAGE_API_REVISION)
                    .header("Content-Type", "application/json").POST(http.json(body)).build(), code());
            List<JsonNode> images = findAll(res, "image");
            JsonNode last = images.isEmpty() ? null : images.get(images.size() - 1);
            if (last == null || !last.hasNonNull("data")) {
                if (out.isEmpty()) throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Gemini returned no image. Try rewording the prompt.");
                break;
            }
            String mime = last.path("mime_type").asText("image/png");
            out.add(new MediaTypes.Clip(java.util.Base64.getDecoder().decode(last.get("data").asText()), mime, null));
        }
        return out;
    }

    // ---- helpers ---------------------------------------------------------

    /** All {type: <type>, data|uri} blocks anywhere in the response, in document order. */
    static List<JsonNode> findAll(JsonNode node, String type) {
        List<JsonNode> out = new ArrayList<>();
        collect(node, type, out);
        return out;
    }

    private static void collect(JsonNode node, String type, List<JsonNode> out) {
        if (node == null) return;
        if (node.isObject()) {
            if (type.equals(node.path("type").asText()) && (node.has("data") || node.has("uri"))) out.add(node);
            node.fields().forEachRemaining(e -> collect(e.getValue(), type, out));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, type, out));
        }
    }

    /** The API has no duration setting; the docs recommend guiding length in the prompt. */
    static String buildPrompt(VideoRequest r) {
        StringBuilder sb = new StringBuilder(r.prompt().strip());
        if (r.durationSeconds() != null) sb.append("\nLength: about ").append(r.durationSeconds()).append(" seconds.");
        if (r.negativePrompt() != null && !r.negativePrompt().isBlank()) sb.append("\nAvoid: ").append(r.negativePrompt().strip());
        if (Boolean.FALSE.equals(r.audio())) sb.append("\nNo audio.");
        return sb.toString();
    }

    /** Finds the first {type: "video"} item anywhere in the response (steps → content). */
    static JsonNode findVideo(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            if ("video".equals(node.path("type").asText()) && (node.has("uri") || node.has("data"))) return node;
            var fields = node.fields();
            while (fields.hasNext()) {
                JsonNode found = findVideo(fields.next().getValue());
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findVideo(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Accepts "files/abc", ".../v1beta/files/abc" or ".../files/abc:download?alt=media". */
    static String fileId(String uri) {
        if (uri == null) return null;
        int i = uri.lastIndexOf("files/");
        if (i < 0) return null;
        String id = uri.substring(i + "files/".length());
        int cut = id.indexOf(':');
        if (cut >= 0) id = id.substring(0, cut);
        cut = id.indexOf('?');
        if (cut >= 0) id = id.substring(0, cut);
        return id.matches("[A-Za-z0-9_-]+") ? id : null;
    }
}
