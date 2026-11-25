package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.crypto.SecretCipher;
import com.leenax.studio.ai.provider.MediaTypes.SpeechRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleCloudTtsAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockServer server;
    private ProviderHttp http;
    private KeyPair pair;
    private String keyFile;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockServer();
        http = new ProviderHttp(mapper);
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        pair = g.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        keyFile = mapper.writeValueAsString(java.util.Map.of("type", "service_account", "project_id", "leenax-test",
                "private_key_id", "abcdef0123456789", "private_key", pem, "client_email", "tts@leenax-test.iam.gserviceaccount.com",
                "token_uri", "https://oauth2.googleapis.com/token"));
        server.on("GET /v1/voices", r -> MockServer.Reply.json(200, """
                {"voices": [
                  {"languageCodes": ["ta-IN"], "name": "ta-IN-Wavenet-A", "ssmlGender": "FEMALE", "naturalSampleRateHertz": 24000},
                  {"languageCodes": ["ta-IN"], "name": "ta-IN-Chirp3-HD-Achernar", "ssmlGender": "FEMALE"},
                  {"languageCodes": ["en-IN"], "name": "en-IN-Neural2-B", "ssmlGender": "MALE"},
                  {"languageCodes": ["de-DE"], "name": "de-DE-Standard-A", "ssmlGender": "FEMALE"},
                  {"languageCodes": ["en-US"], "name": "en-US-Standard-C", "ssmlGender": "SSML_VOICE_GENDER_UNSPECIFIED"}
                ]}"""));
        server.on("POST /v1/text:synthesize", r -> MockServer.Reply.json(200,
                "{\"audioContent\":\"" + Base64.getEncoder().encodeToString("ID3TAMIL".getBytes()) + "\"}"));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    GoogleCloudTtsAdapter adapter(Clock clock) {
        return new GoogleCloudTtsAdapter(http, server.url(), server.url() + "/token", clock);
    }

    static Clock at(long epochSecond) {
        return Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }

    @Test
    void aServiceAccountSignsAJwtSwapsItForATokenAndReusesTheToken() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        server.on("POST /token", r -> MockServer.Reply.json(200, "{\"access_token\":\"ya29.token" + issued.incrementAndGet() + "\",\"expires_in\":3599,\"token_type\":\"Bearer\"}"));
        GoogleCloudTtsAdapter a = adapter(at(1_800_000_000L));
        var creds = new ProviderCredentials(keyFile);
        a.verify(creds);
        a.voices(creds);
        assertEquals(1, issued.get(), "the token is reused while it's valid");
        assertEquals("Bearer ya29.token1", server.last("GET", "/v1/voices").header("Authorization"));
        assertNull(server.last("GET", "/v1/voices").header("x-goog-api-key"));

        String form = server.last("POST", "/token").body();
        assertTrue(form.startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="), form);
        String jwt = URLDecoder.decode(form.substring(form.indexOf("assertion=") + 10), StandardCharsets.UTF_8);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("RS256", header.path("alg").asText());
        assertEquals("abcdef0123456789", header.path("kid").asText());
        assertEquals("tts@leenax-test.iam.gserviceaccount.com", claims.path("iss").asText());
        assertEquals("https://www.googleapis.com/auth/cloud-platform", claims.path("scope").asText());
        assertEquals("https://oauth2.googleapis.com/token", claims.path("aud").asText());
        assertEquals(1_800_000_000L, claims.path("iat").asLong());
        assertEquals(1_800_003_600L, claims.path("exp").asLong());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(pair.getPublic());
        s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(s.verify(Base64.getUrlDecoder().decode(parts[2])), "signed with the key file's private key");

        // close to expiry, a new token is fetched
        GoogleCloudTtsAdapter later = adapter(at(1_800_000_000L));
        later.voices(creds);
        assertEquals(2, issued.get(), "a new adapter has no cached token");
    }

    @Test
    void anApiKeyIsSentAsAHeader() {
        GoogleCloudTtsAdapter a = adapter(Clock.systemUTC());
        a.verify(new ProviderCredentials("AIzaTestKey1234567890"));
        var req = server.last("GET", "/v1/voices?languageCode=ta-IN");
        assertEquals("AIzaTestKey1234567890", req.header("x-goog-api-key"));
        assertNull(req.header("Authorization"));
    }

    @Test
    void badKeyFilesAreRefusedWithoutCallingGoogle() {
        GoogleCloudTtsAdapter a = adapter(Clock.systemUTC());
        ProviderException e = assertThrows(ProviderException.class, () -> a.verify(new ProviderCredentials("{\"type\":\"authorized_user\"}")));
        assertEquals(ProviderErrorKind.INVALID_CREDENTIALS, e.kind());
        String elsewhere = keyFile.replace("https://oauth2.googleapis.com/token", "https://attacker.example/token");
        e = assertThrows(ProviderException.class, () -> a.verify(new ProviderCredentials(elsewhere)));
        assertTrue(e.getMessage().contains("isn't Google's"), e.getMessage());
        assertThrows(ProviderException.class, () -> a.verify(new ProviderCredentials("{not json")));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void voicesAreTamilAndEnglishWithTheirTypeAndPrice() {
        List<MediaTypes.VoiceInfo> v = adapter(Clock.systemUTC()).voices(new ProviderCredentials("AIzaTestKey1234567890"));
        assertEquals(List.of("ta-IN-Chirp3-HD-Achernar", "ta-IN-Wavenet-A", "en-IN-Neural2-B", "en-US-Standard-C"), v.stream().map(MediaTypes.VoiceInfo::id).toList());
        assertEquals("WaveNet", v.get(1).category());
        assertEquals("female", v.get(1).gender());
        assertNull(v.get(3).gender());
        assertTrue(v.get(0).description().contains("US$30"));
    }

    @Test
    void speechIsSsmlWithBreaksAndEscapedText() throws Exception {
        GoogleCloudTtsAdapter a = adapter(Clock.systemUTC());
        String text = "A < B & C" + a.pauseMarkup(0.75) + "வணக்கம்";
        var clip = a.synthesize(new SpeechRequest(null, "ta-IN-Wavenet-A", text, "ta", null, null, null, null, null, null, null),
                new ProviderCredentials("AIzaTestKey1234567890"));
        assertEquals("ID3TAMIL", new String(clip.bytes()));
        assertEquals("audio/mpeg", clip.mimeType());
        JsonNode body = mapper.readTree(server.last("POST", "/v1/text:synthesize").body());
        assertEquals("<speak>A &lt; B &amp; C<break time=\"750ms\"/>வணக்கம்</speak>", body.path("input").path("ssml").asText());
        assertEquals("ta-IN", body.path("voice").path("languageCode").asText());
        assertEquals("ta-IN-Wavenet-A", body.path("voice").path("name").asText());
        assertEquals("MP3", body.path("audioConfig").path("audioEncoding").asText());
    }

    @Test
    void chirpVoicesGetPlainTextBecauseTheyDontTakeSsml() throws Exception {
        GoogleCloudTtsAdapter a = adapter(Clock.systemUTC());
        String text = "First" + a.pauseMarkup(1.0) + "second" + a.pauseMarkup(0.3) + "third";
        a.synthesize(new SpeechRequest(null, "ta-IN-Chirp3-HD-Achernar", text, "ta", null, null, null, null, null, null, null),
                new ProviderCredentials("AIzaTestKey1234567890"));
        JsonNode input = mapper.readTree(server.last("POST", "/v1/text:synthesize").body()).path("input");
        assertFalse(input.has("ssml"));
        assertEquals("First. second, third", input.path("text").asText());
    }

    @Test
    void googleErrorsAreReportedAsProviderErrors() {
        server.on("POST /v1/text:synthesize", r -> MockServer.Reply.json(403, "{\"error\":{\"code\":403,\"message\":\"API keys are not supported by this API.\",\"status\":\"PERMISSION_DENIED\"}}"));
        GoogleCloudTtsAdapter a = adapter(Clock.systemUTC());
        ProviderException e = assertThrows(ProviderException.class, () -> a.synthesize(
                new SpeechRequest(null, "ta-IN-Wavenet-A", "hi", "ta", null, null, null, null, null, null, null), new ProviderCredentials("AIzaTestKey1234567890")));
        assertFalse(e.getMessage().contains("AIzaTestKey"), "the key isn't in the message");
    }

    @Test
    void theKeyHintOfAKeyFileIsTheEndOfItsId() {
        assertEquals("6789", SecretCipher.hint(keyFile));
        assertEquals("7890", SecretCipher.hint("AIzaTestKey1234567890"));
        assertEquals(1400, new GoogleCloudTtsAdapter(http).maxChars(null));
        assertEquals(ProviderCode.GOOGLE_CLOUD_TTS, ProviderRegistry.defaults(http).speech(ProviderCode.GOOGLE_CLOUD_TTS).orElseThrow().code());
    }
}
