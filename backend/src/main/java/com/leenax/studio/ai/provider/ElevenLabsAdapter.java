package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.ai.provider.MediaTypes.Clip;
import com.leenax.studio.ai.provider.MediaTypes.Sample;
import com.leenax.studio.ai.provider.MediaTypes.Segment;
import com.leenax.studio.ai.provider.MediaTypes.SpeechRequest;
import com.leenax.studio.ai.provider.MediaTypes.Transcript;
import com.leenax.studio.ai.provider.MediaTypes.VoiceInfo;
import com.leenax.studio.ai.provider.MediaTypes.Word;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ElevenLabs (https://elevenlabs.io/docs/api-reference).
 *   POST /v1/text-to-speech/{voice_id}?output_format=mp3_44100_128   { text, model_id, language_code, voice_settings }
 *   GET  /v2/voices                                                    voices[], has_more, next_page_token
 *   POST /v1/voices/add          (multipart: name, files, remove_background_noise)   → voice_id
 *   DELETE /v1/voices/{voice_id}
 *   POST /v1/speech-to-text      (multipart: model_id, file, language_code, timestamps_granularity=word)
 *   POST /v1/sound-generation    { text, duration_seconds 0.5–30, loop, model_id }
 *   GET  /v1/user/subscription   character_count, character_limit, next_character_count_reset_unix
 * Auth header: xi-api-key.
 */
public class ElevenLabsAdapter implements SpeechProvider, TranscriptionProvider, SoundProvider {

    public static final String DEFAULT_TTS_MODEL = "eleven_v3";
    public static final String STT_MODEL = "scribe_v2";
    public static final String SFX_MODEL = "eleven_text_to_sound_v3";
    private static final String OUTPUT = "mp3_44100_128";

    private final ProviderHttp http;
    private final String baseUrl;

    public ElevenLabsAdapter(ProviderHttp http) {
        this(http, "https://api.elevenlabs.io");
    }

    public ElevenLabsAdapter(ProviderHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.ELEVENLABS;
    }

    private HttpRequest.Builder req(String path, Duration timeout, ProviderCredentials c) {
        return ProviderHttp.request(baseUrl + path, timeout).header("xi-api-key", c.apiKey());
    }

    // ---- account ---------------------------------------------------------------

    public void verify(ProviderCredentials credentials) {
        http.send(req("/v1/user/subscription", Duration.ofSeconds(20), credentials).GET().build(), code());
    }

    public List<BalanceSnapshot> balances(ProviderCredentials credentials) {
        JsonNode sub = http.send(req("/v1/user/subscription", Duration.ofSeconds(20), credentials).GET().build(), code());
        long used = sub.path("character_count").asLong(0);
        long limit = sub.path("character_limit").asLong(0);
        JsonNode reset = sub.get("next_character_count_reset_unix");
        Instant resetsAt = reset != null && reset.isNumber() && reset.asLong() > 0 ? Instant.ofEpochSecond(reset.asLong()) : null;
        String tier = sub.path("tier").asText("");
        String label = "Monthly credits" + (tier.isBlank() ? "" : " (" + tier + " plan)");
        return List.of(new BalanceSnapshot(label, "free".equalsIgnoreCase(tier), BigDecimal.valueOf(Math.max(0, limit - used)),
                "CREDITS", resetsAt));
    }

    // ---- speech ----------------------------------------------------------------

    @Override
    public List<VoiceInfo> voices(ProviderCredentials credentials) {
        List<VoiceInfo> out = new ArrayList<>();
        String token = null;
        for (int page = 0; page < 5; page++) {
            String q = "/v2/voices?page_size=100" + (token != null ? "&next_page_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) : "");
            JsonNode res = http.send(req(q, Duration.ofSeconds(20), credentials).GET().build(), code());
            for (JsonNode v : res.path("voices")) {
                JsonNode labels = v.path("labels");
                String category = v.path("category").asText("");
                out.add(new VoiceInfo(v.path("voice_id").asText(), v.path("name").asText(), category,
                        text(labels, "gender"), text(labels, "language"), text(labels, "accent"),
                        v.hasNonNull("description") ? v.get("description").asText() : text(labels, "description"),
                        v.hasNonNull("preview_url") ? v.get("preview_url").asText() : null,
                        "cloned".equals(category) || "professional".equals(category)));
            }
            if (!res.path("has_more").asBoolean(false)) break;
            token = res.path("next_page_token").asText(null);
            if (token == null) break;
        }
        return out;
    }

    @Override
    public Clip synthesize(SpeechRequest r, ProviderCredentials credentials) {
        String model = r.model() == null || r.model().isBlank() ? DEFAULT_TTS_MODEL : r.model();
        Map<String, Object> settings = new LinkedHashMap<>();
        if (r.stability() != null) settings.put("stability", clamp(r.stability(), 0, 1));
        if (r.similarity() != null) settings.put("similarity_boost", clamp(r.similarity(), 0, 1));
        if (r.exaggeration() != null) settings.put("style", clamp(r.exaggeration(), 0, 1));
        if (r.speed() != null) settings.put("speed", clamp(r.speed(), 0.7, 1.2));
        settings.put("use_speaker_boost", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", r.text());
        body.put("model_id", model);
        // language_code is ignored by multilingual_v2 models, so only send it to models that use it.
        if (r.languageCode() != null && !model.startsWith("eleven_multilingual_v2")) body.put("language_code", r.languageCode());
        body.put("voice_settings", settings);
        if (r.previousText() != null) body.put("previous_text", r.previousText());
        if (r.nextText() != null) body.put("next_text", r.nextText());

        var res = http.sendForBytes(req("/v1/text-to-speech/" + enc(r.voiceId()) + "?output_format=" + OUTPUT, Duration.ofMinutes(3), credentials)
                .header("Content-Type", "application/json").header("Accept", "audio/mpeg")
                .POST(http.json(body)).build(), code());
        return new Clip(res.bytes(), "audio/mpeg", r.text().length());
    }

    @Override
    public int maxChars(String model) {
        if (model == null || model.isBlank() || model.startsWith("eleven_v3")) return 4800;      // 5,000 limit
        if (model.startsWith("eleven_flash")) return 30000;
        return 9500;                                                                           // 10,000 limit
    }

    /** ElevenLabs reads SSML-style break tags (max 3 s each). */
    @Override
    public String pauseMarkup(double seconds) {
        StringBuilder sb = new StringBuilder();
        double left = Math.max(0.1, seconds);
        while (left > 0.05) {
            double chunk = Math.min(3.0, left);
            sb.append(String.format(Locale.ROOT, "<break time=\"%.1fs\" />", chunk));
            left -= chunk;
        }
        return sb.toString();
    }

    @Override
    public boolean supportsCloning() {
        return true;
    }

    @Override
    public VoiceInfo cloneVoice(String name, String description, List<Sample> samples, boolean removeBackgroundNoise, ProviderCredentials credentials) {
        Multipart form = new Multipart().field("name", name).field("remove_background_noise", String.valueOf(removeBackgroundNoise));
        if (description != null && !description.isBlank()) form.field("description", description);
        for (Sample s : samples) form.file("files", s.filename(), s.mimeType(), s.bytes());
        JsonNode res = http.send(req("/v1/voices/add", Duration.ofMinutes(3), credentials)
                .header("Content-Type", form.contentType()).POST(form.publisher()).build(), code());
        String id = ProviderHttp.text(res, "/voice_id");
        if (id == null) throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "ElevenLabs returned no voice id");
        if (res.path("requires_verification").asBoolean(false)) {
            throw new ProviderException(ProviderErrorKind.CONTENT_POLICY_REJECTED,
                    "ElevenLabs needs to verify this voice before it can be used. Finish verification in your ElevenLabs account.");
        }
        return new VoiceInfo(id, name, "cloned", null, null, null, description, null, true);
    }

    @Override
    public void deleteVoice(String voiceId, ProviderCredentials credentials) {
        http.send(req("/v1/voices/" + enc(voiceId), Duration.ofSeconds(30), credentials).DELETE().build(), code());
    }

    // ---- transcription ---------------------------------------------------------

    @Override
    public long maxBytes() {
        return 1024L * 1024 * 1024; // the API allows up to 5 GB; keep uploads sane
    }

    @Override
    public Transcript transcribe(Path audio, String mimeType, String languageCode, ProviderCredentials credentials) {
        byte[] data;
        try {
            data = Files.readAllBytes(audio);
        } catch (IOException e) {
            throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Could not read the audio file");
        }
        Multipart form = new Multipart()
                .field("model_id", STT_MODEL)
                .field("timestamps_granularity", "word")
                .field("tag_audio_events", "false")
                .field("language_code", languageCode)
                .file("file", audio.getFileName().toString(), mimeType, data);
        JsonNode res = http.send(req("/v1/speech-to-text", Duration.ofMinutes(15), credentials)
                .header("Content-Type", form.contentType()).POST(form.publisher()).build(), code());
        List<Word> words = new ArrayList<>();
        for (JsonNode w : res.path("words")) {
            if (!"word".equals(w.path("type").asText("word"))) continue;
            String t = w.path("text").asText("").strip();
            if (!t.isEmpty()) words.add(new Word(t, w.path("start").asDouble(), w.path("end").asDouble()));
        }
        return new Transcript(res.path("language_code").asText(languageCode), res.path("text").asText(""), words, List.<Segment>of());
    }

    // ---- sound effects -----------------------------------------------------------

    @Override
    public Clip soundEffect(String prompt, Double durationSeconds, boolean loop, ProviderCredentials credentials) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", prompt);
        body.put("model_id", SFX_MODEL);
        body.put("loop", loop);
        if (durationSeconds != null) body.put("duration_seconds", clamp(durationSeconds, 0.5, 30));
        var res = http.sendForBytes(req("/v1/sound-generation?output_format=" + OUTPUT, Duration.ofMinutes(3), credentials)
                .header("Content-Type", "application/json").header("Accept", "audio/mpeg").POST(http.json(body)).build(), code());
        Integer cost = null;
        String c = res.header("character-cost");
        if (c != null) {
            try {
                cost = Integer.parseInt(c.strip());
            } catch (NumberFormatException ignored) {
                // leave unknown
            }
        }
        return new Clip(res.bytes(), "audio/mpeg", cost);
    }

    // ---- helpers ---------------------------------------------------------------

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
