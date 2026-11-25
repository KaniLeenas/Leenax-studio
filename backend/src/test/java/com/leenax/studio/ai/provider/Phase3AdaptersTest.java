package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.provider.MediaTypes.ImageRequest;
import com.leenax.studio.ai.provider.MediaTypes.Sample;
import com.leenax.studio.ai.provider.MediaTypes.SpeechRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase3AdaptersTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderCredentials key = new ProviderCredentials("sk-test-key-4242");
    private MockServer server;
    private ProviderHttp http;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockServer();
        http = new ProviderHttp(mapper);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    // ---- ElevenLabs ------------------------------------------------------------

    @Test
    void elevenLabsSpeechSendsSettingsAndReturnsMp3() throws Exception {
        ElevenLabsAdapter el = new ElevenLabsAdapter(http, server.url());
        server.on("POST /v1/text-to-speech/voice123", r -> MockServer.Reply.bytes("ID3AUDIO"));
        var clip = el.synthesize(new SpeechRequest("eleven_v3", "voice123", "வணக்கம் நண்பர்களே", "ta", null,
                0.4, 0.8, 0.5, 1.5, null, null), key);
        assertEquals("ID3AUDIO", new String(clip.bytes()));
        var req = server.last("POST", "/v1/text-to-speech/voice123");
        assertEquals("sk-test-key-4242", req.header("xi-api-key"));
        assertTrue(req.pathAndQuery().contains("output_format=mp3_44100_128"));
        JsonNode body = mapper.readTree(req.body());
        assertEquals("ta", body.get("language_code").asText());
        assertEquals("eleven_v3", body.get("model_id").asText());
        assertEquals(1.2, body.at("/voice_settings/speed").asDouble(), 1e-9);
        assertEquals(0.4, body.at("/voice_settings/stability").asDouble(), 1e-9);
    }

    @Test
    void elevenLabsMultilingualV2SkipsLanguageCode() throws Exception {
        ElevenLabsAdapter el = new ElevenLabsAdapter(http, server.url());
        server.on("POST /v1/text-to-speech/v", r -> MockServer.Reply.bytes("A"));
        el.synthesize(new SpeechRequest("eleven_multilingual_v2", "v", "hi", "en", null, null, null, null, null, null, null), key);
        assertTrue(mapper.readTree(server.last("POST", "/v1/text-to-speech/v").body()).get("language_code") == null);
    }

    @Test
    void elevenLabsErrorsUseDetailMessage() {
        ElevenLabsAdapter el = new ElevenLabsAdapter(http, server.url());
        server.on("POST /v1/text-to-speech/v", r -> MockServer.Reply.json(401,
                "{\"detail\":{\"status\":\"invalid_api_key\",\"message\":\"Invalid API key\"}}"));
        ProviderException e = assertThrows(ProviderException.class, () -> el.synthesize(
                new SpeechRequest(null, "v", "hi", null, null, null, null, null, null, null, null), key));
        assertEquals(ProviderErrorKind.INVALID_CREDENTIALS, e.kind());
        assertTrue(e.getMessage().contains("Invalid API key"));
    }

    @Test
    void elevenLabsVoicesPaginateAndMarkClones() {
        ElevenLabsAdapter el = new ElevenLabsAdapter(http, server.url());
        server.on("GET /v2/voices", r -> r.pathAndQuery().contains("next_page_token")
                ? MockServer.Reply.json(200, "{\"voices\":[{\"voice_id\":\"c1\",\"name\":\"My voice\",\"category\":\"cloned\",\"labels\":{}}],\"has_more\":false}")
                : MockServer.Reply.json(200, "{\"voices\":[{\"voice_id\":\"p1\",\"name\":\"Sarah\",\"category\":\"premade\",\"labels\":{\"gender\":\"female\",\"accent\":\"american\"},\"preview_url\":\"https://x/p.mp3\"}],\"has_more\":true,\"next_page_token\":\"t2\"}"));
        var voices = el.voices(key);
        assertEquals(2, voices.size());
        assertEquals("female", voices.get(0).gender());
        assertTrue(voices.get(1).cloned());
    }

    @Test
    void elevenLabsCloneSendsMultipartFiles() {
        ElevenLabsAdapter el = new ElevenLabsAdapter(http, server.url());
        server.on("POST /v1/voices/add", r -> MockServer.Reply.json(200, "{\"voice_id\":\"new1\",\"requires_verification\":false}"));
        var v = el.cloneVoice("Kani narration", "My own voice", List.of(new Sample("RIFFDATA".getBytes(), "take1.wav", "audio/wav")), true, key);
        assertEquals("new1", v.id());
        var req = server.last("POST", "/v1/voices/add");
        assertTrue(req.header("Content-Type").startsWith("multipart/form-data; boundary="));
        assertTrue(req.body().contains("name=\"files\"; filename=\"take1.wav\""));
        assertTrue(req.body().contains("Kani narration"));
        assertTrue(req.body().contains("RIFFDATA"));
    }

    @Test
    void elevenLabsTranscriptKeepsOnlyWords() throws Exception {
        ElevenLabsAdapter el = new ElevenLabsAdapter(http, server.url());
        server.on("POST /v1/speech-to-text", r -> MockServer.Reply.json(200, """
                {"language_code":"ta","text":"வணக்கம் நண்பர்களே","words":[
                 {"text":"வணக்கம்","start":0.1,"end":0.6,"type":"word"},{"text":" ","start":0.6,"end":0.7,"type":"spacing"},
                 {"text":"நண்பர்களே","start":0.7,"end":1.4,"type":"word"}]}"""));
        Path f = Files.createTempFile("a", ".mp3");
        Files.write(f, new byte[]{1, 2});
        var t = el.transcribe(f, "audio/mpeg", "ta", key);
        Files.delete(f);
        assertEquals(2, t.words().size());
        assertEquals(0.7, t.words().get(1).start(), 1e-9);
        String body = server.last("POST", "/v1/speech-to-text").body();
        assertTrue(body.contains("scribe_v2") && body.contains("timestamps_granularity"));
    }

    @Test
    void elevenLabsSoundEffectsAndBalance() throws Exception {
        ElevenLabsAdapter el = new ElevenLabsAdapter(http, server.url());
        server.on("POST /v1/sound-generation", r -> new MockServer.Reply(200, "SFX", "audio/mpeg"));
        var clip = el.soundEffect("temple bell", 45.0, false, key);
        assertEquals("SFX", new String(clip.bytes()));
        assertEquals(30.0, mapper.readTree(server.last("POST", "/v1/sound-generation").body()).get("duration_seconds").asDouble(), 1e-9);

        server.on("GET /v1/user/subscription", r -> MockServer.Reply.json(200,
                "{\"character_count\":12000,\"character_limit\":100000,\"next_character_count_reset_unix\":1798761600,\"tier\":\"creator\",\"status\":\"active\"}"));
        var b = el.balances(key);
        assertEquals(new BigDecimal("88000"), b.get(0).amount());
        assertTrue(b.get(0).label().contains("creator"));
    }

    @Test
    void elevenLabsPausesSplitIntoThreeSecondBreaks() {
        ElevenLabsAdapter el = new ElevenLabsAdapter(http, server.url());
        assertEquals("<break time=\"1.5s\" />", el.pauseMarkup(1.5));
        assertEquals("<break time=\"3.0s\" /><break time=\"1.0s\" />", el.pauseMarkup(4));
    }

    // ---- Gemini ----------------------------------------------------------------

    @Test
    void geminiTtsUsesUserInputAndStyleAnnotation() throws Exception {
        GeminiAdapter g = new GeminiAdapter(http, server.url());
        String wav = Base64.getEncoder().encodeToString("RIFFWAV".getBytes());
        server.on("POST /v1beta/interactions", r -> MockServer.Reply.json(200,
                "{\"id\":\"i\",\"status\":\"completed\",\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"mime_type\":\"audio/wav\",\"data\":\"" + wav + "\"}]}]}"));
        var clip = g.synthesize(new SpeechRequest(null, "Kore", "Hello <short pause> friends", null, "warm storytelling",
                null, null, null, null, null, null), key);
        assertEquals("RIFFWAV", new String(clip.bytes()));
        assertEquals("audio/wav", clip.mimeType());
        JsonNode body = mapper.readTree(server.last("POST", "/v1beta/interactions").body());
        assertEquals("gemini-3.8-flash-tts", body.get("model").asText());
        assertEquals("user_input", body.at("/input/0/type").asText());
        assertEquals("warm storytelling", body.at("/input/0/content/0/annotations/0/style").asText());
        assertEquals("Kore", body.at("/generation_config/speech_config/0/voice").asText());
        assertEquals("audio", body.at("/response_format/type").asText());
    }

    @Test
    void geminiImagesSendRevisionHeaderAndReferences() throws Exception {
        GeminiAdapter g = new GeminiAdapter(http, server.url());
        String png = Base64.getEncoder().encodeToString("PNGDATA".getBytes());
        server.on("POST /v1beta/interactions", r -> MockServer.Reply.json(200,
                "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"here\"},{\"type\":\"image\",\"mime_type\":\"image/png\",\"data\":\"" + png + "\"}]}]}"));
        var out = g.generate(new ImageRequest("gemini-nano-banana-2.1", "a kolam", null, "16:9", "2K", 2, null, false,
                List.of(new Sample(new byte[]{5}, "ref.jpg", "image/jpeg"))), key);
        assertEquals(2, out.size());
        assertEquals("PNGDATA", new String(out.get(0).bytes()));
        var req = server.last("POST", "/v1beta/interactions");
        assertEquals("2026-05-20", req.header("Api-Revision"));
        JsonNode body = mapper.readTree(req.body());
        assertEquals("16:9", body.at("/response_format/aspect_ratio").asText());
        assertEquals("2K", body.at("/response_format/image_size").asText());
        assertEquals("image/jpeg", body.at("/input/1/mime_type").asText());
    }

    // ---- OpenAI ----------------------------------------------------------------

    @Test
    void openAiImagesGenerateAndEdit() throws Exception {
        OpenAiAdapter o = new OpenAiAdapter(http, server.url());
        String png = Base64.getEncoder().encodeToString("IMG".getBytes());
        server.on("POST /v1/images/generations", r -> MockServer.Reply.json(200, "{\"data\":[{\"b64_json\":\"" + png + "\"},{\"b64_json\":\"" + png + "\"}]}"));
        var out = o.generate(new ImageRequest("gpt-image-2.5-flare", "thumbnail", "1280x720", null, null, 2, "high", true, List.of()), key);
        assertEquals(2, out.size());
        JsonNode body = mapper.readTree(server.last("POST", "/v1/images/generations").body());
        assertEquals("1280x720", body.get("size").asText());
        assertEquals("transparent", body.get("background").asText());
        assertEquals(2, body.get("n").asInt());

        server.on("POST /v1/images/edits", r -> MockServer.Reply.json(200, "{\"data\":[{\"b64_json\":\"" + png + "\"}]}"));
        o.generate(new ImageRequest("gpt-image-2.5-flare", "same style", "1024x1024", null, null, 1, null, false,
                List.of(new Sample(new byte[]{1}, "a.png", "image/png"))), key);
        assertTrue(server.last("POST", "/v1/images/edits").body().contains("name=\"image[]\"; filename=\"a.png\""));
    }

    @Test
    void openAiTranscriptionAsksForWordAndSegmentTimestamps() throws Exception {
        OpenAiAdapter o = new OpenAiAdapter(http, server.url());
        server.on("POST /v1/audio/transcriptions", r -> MockServer.Reply.json(200, """
                {"language":"english","text":"Hello there","duration":1.2,
                 "words":[{"word":"Hello","start":0.0,"end":0.4},{"word":"there","start":0.5,"end":0.9}],
                 "segments":[{"id":0,"start":0.0,"end":0.9,"text":" Hello there"}]}"""));
        Path f = Files.createTempFile("a", ".mp3");
        Files.write(f, new byte[]{1});
        var t = o.transcribe(f, "audio/mpeg", "en", key);
        Files.delete(f);
        assertEquals(2, t.words().size());
        assertEquals("Hello there", t.segments().get(0).text());
        String body = server.last("POST", "/v1/audio/transcriptions").body();
        assertTrue(body.contains("verbose_json") && body.contains("whisper-1"));
        assertTrue(body.split("timestamp_granularities\\[\\]").length == 3);
    }

    @Test
    void registryRoutesBalancesAndVerification() {
        ProviderRegistry reg = new ProviderRegistry(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                new ElevenLabsAdapter(http, server.url()));
        server.on("GET /v1/user/subscription", r -> MockServer.Reply.json(200, "{\"character_count\":0,\"character_limit\":10000}"));
        reg.verify(ProviderCode.ELEVENLABS, key);
        assertEquals(new BigDecimal("10000"), reg.balances(ProviderCode.ELEVENLABS, key).get(0).amount());
        assertTrue(reg.balances(ProviderCode.OPENAI, key).isEmpty());
    }
}
