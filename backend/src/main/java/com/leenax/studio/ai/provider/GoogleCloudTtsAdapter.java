package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Cloud Text-to-Speech (v1), used for its Tamil voices.
 *   GET  /v1/voices[?languageCode=]  → voices[] {languageCodes[], name, ssmlGender, naturalSampleRateHertz}
 *   POST /v1/text:synthesize {input {ssml | text}, voice {languageCode, name}, audioConfig {audioEncoding: MP3}} → {audioContent (base64)}
 *
 * Two ways to sign in, chosen by what the administrator pastes as the key:
 *   - a service account key file (JSON): Leenax signs a JWT (RS256) and swaps it at Google's token endpoint for a
 *     short-lived access token, cached in memory until shortly before it expires;
 *   - an API key, sent in the x-goog-api-key header. Google documents service accounts for this API, not API keys, so a
 *     key that Google refuses is reported as such.
 *
 * Pauses are SSML breaks. Chirp 3: HD voices don't take SSML, so for them the text is sent plain.
 * A request is at most 5,000 bytes; Tamil is three bytes a letter, so chunks are kept to 1,400 characters.
 */
public class GoogleCloudTtsAdapter implements SpeechProvider {

    public static final String MODEL = "cloud-tts";
    static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    static final String TOKEN_HOST = "oauth2.googleapis.com";
    /** Languages offered: Tamil and the English accents a Sri Lankan or Indian audience expects. */
    static final List<String> LANGUAGES = List.of("ta-IN", "en-IN", "en-GB", "en-US", "en-AU");

    private final ProviderHttp http;
    private final String baseUrl;
    private final String tokenUrlOverride;
    private final Clock clock;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    record Token(String value, long expiresAtEpochSecond) {
    }

    public GoogleCloudTtsAdapter(ProviderHttp http) {
        this(http, "https://texttospeech.googleapis.com", null, Clock.systemUTC());
    }

    /** For tests: another API address, and a token endpoint to use instead of the one in the key file. */
    public GoogleCloudTtsAdapter(ProviderHttp http, String baseUrl, String tokenUrlOverride, Clock clock) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.tokenUrlOverride = tokenUrlOverride;
        this.clock = clock;
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.GOOGLE_CLOUD_TTS;
    }

    // ---- signing in ------------------------------------------------------------------------------

    /** A service account key file, as Google downloads it. */
    record ServiceAccount(String clientEmail, String privateKeyId, PrivateKey key, String tokenUri) {
    }

    static boolean isServiceAccount(String secret) {
        return secret.stripLeading().startsWith("{");
    }

    static ServiceAccount serviceAccount(String json, ObjectMapper mapper) {
        JsonNode n;
        try {
            n = mapper.readTree(json);
        } catch (Exception e) {
            throw new ProviderException(ProviderErrorKind.INVALID_CREDENTIALS, "That isn't a valid service account key file (JSON)");
        }
        if (!"service_account".equals(n.path("type").asText())) {
            throw new ProviderException(ProviderErrorKind.INVALID_CREDENTIALS, "The key file isn't a service account key (its \"type\" should be \"service_account\")");
        }
        String email = n.path("client_email").asText("");
        String pem = n.path("private_key").asText("");
        if (email.isBlank() || pem.isBlank()) throw new ProviderException(ProviderErrorKind.INVALID_CREDENTIALS, "The key file has no client_email or private_key");
        String tokenUri = n.path("token_uri").asText("https://" + TOKEN_HOST + "/token");
        URI u = URI.create(tokenUri);
        if (!"https".equals(u.getScheme()) || !TOKEN_HOST.equals(u.getHost())) {
            throw new ProviderException(ProviderErrorKind.INVALID_CREDENTIALS, "The key file's token_uri isn't Google's (" + TOKEN_HOST + ")");
        }
        try {
            String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            return new ServiceAccount(email, n.path("private_key_id").asText(null), key, tokenUri);
        } catch (Exception e) {
            throw new ProviderException(ProviderErrorKind.INVALID_CREDENTIALS, "The key file's private_key can't be read");
        }
    }

    /** The signed JWT that is swapped for an access token (RFC 7523). */
    static String assertion(ServiceAccount sa, String audience, long now) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (sa.privateKeyId() != null) header.put("kid", sa.privateKeyId());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", sa.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", audience);
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        try {
            ObjectMapper m = new ObjectMapper();
            String signingInput = b64.encodeToString(m.writeValueAsBytes(header)) + "." + b64.encodeToString(m.writeValueAsBytes(claims));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(sa.key());
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64.encodeToString(s.sign());
        } catch (Exception e) {
            throw new ProviderException(ProviderErrorKind.INVALID_CREDENTIALS, "Couldn't sign in with the service account key");
        }
    }

    /** The header that signs a request in: a bearer token for a service account, or the API key. */
    Map<String, String> auth(ProviderCredentials c) {
        String secret = c.apiKey();
        if (!isServiceAccount(secret)) return Map.of("x-goog-api-key", secret);
        String cacheKey = sha256(secret);
        long now = clock.instant().getEpochSecond();
        Token t = tokens.get(cacheKey);
        if (t == null || t.expiresAtEpochSecond() - 120 < now) {
            ServiceAccount sa = serviceAccount(secret, http.mapper());
            String tokenUrl = tokenUrlOverride != null ? tokenUrlOverride : sa.tokenUri();
            String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                    + "&assertion=" + URLEncoder.encode(assertion(sa, sa.tokenUri(), now), StandardCharsets.UTF_8);
            JsonNode res = http.send(ProviderHttp.request(tokenUrl, Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), code());
            String access = res.path("access_token").asText("");
            if (access.isBlank()) throw new ProviderException(ProviderErrorKind.INVALID_CREDENTIALS, "Google didn't return an access token for the service account");
            t = new Token(access, now + res.path("expires_in").asLong(3600));
            tokens.put(cacheKey, t);
        }
        return Map.of("Authorization", "Bearer " + t.value());
    }

    private HttpRequest.Builder req(String path, Duration timeout, ProviderCredentials c) {
        HttpRequest.Builder b = ProviderHttp.request(baseUrl + path, timeout);
        auth(c).forEach(b::header);
        return b;
    }

    // ---- voices ----------------------------------------------------------------------------------

    public void verify(ProviderCredentials credentials) {
        http.send(req("/v1/voices?languageCode=ta-IN", Duration.ofSeconds(20), credentials).GET().build(), code());
    }

    @Override
    public List<MediaTypes.VoiceInfo> voices(ProviderCredentials credentials) {
        JsonNode res = http.send(req("/v1/voices", Duration.ofSeconds(30), credentials).GET().build(), code());
        List<MediaTypes.VoiceInfo> out = new ArrayList<>();
        for (JsonNode v : res.path("voices")) {
            String name = v.path("name").asText("");
            String lang = v.path("languageCodes").path(0).asText("");
            if (name.isBlank() || !LANGUAGES.contains(lang)) continue;
            String gender = v.path("ssmlGender").asText("").toLowerCase(Locale.ROOT);
            out.add(new MediaTypes.VoiceInfo(name, name, tier(name), gender.isBlank() || gender.equals("ssml_voice_gender_unspecified") ? null : gender,
                    lang, null, tierNote(tier(name)), null, false));
        }
        out.sort((a, b) -> {
            int la = LANGUAGES.indexOf(a.language()), lb = LANGUAGES.indexOf(b.language());
            return la != lb ? la - lb : a.id().compareTo(b.id());
        });
        return out;
    }

    /** The voice type, from the voice's name (ta-IN-Wavenet-A → WaveNet). Each type has its own price. */
    static String tier(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("chirp3-hd") || n.contains("chirp-hd")) return "Chirp 3: HD";
        if (n.contains("studio")) return "Studio";
        if (n.contains("neural2")) return "Neural2";
        if (n.contains("wavenet")) return "WaveNet";
        if (n.contains("polyglot")) return "Polyglot";
        if (n.contains("standard")) return "Standard";
        return "Other";
    }

    /** Google's list prices (checked 24 November 2026), per million characters. */
    static String tierNote(String tier) {
        return switch (tier) {
            case "Standard", "WaveNet" -> "US$4 per million characters; 4 million free a month";
            case "Neural2", "Polyglot" -> "US$16 per million characters; 1 million free a month";
            case "Chirp 3: HD" -> "US$30 per million characters; 1 million free a month";
            case "Studio" -> "US$160 per million characters";
            default -> null;
        };
    }

    static boolean takesSsml(String voice) {
        return !"Chirp 3: HD".equals(tier(voice));
    }

    // ---- speech ----------------------------------------------------------------------------------

    static final String PAUSE = "\u0001pause:";
    static final Pattern PAUSE_MARK = Pattern.compile("\u0001pause:(\\d+)\u0001");

    @Override
    public String pauseMarkup(double seconds) {
        long ms = Math.max(100, Math.min(10_000, Math.round(seconds * 1000)));
        return PAUSE + ms + "\u0001";
    }

    @Override
    public int maxChars(String model) {
        return 1400;
    }

    @Override
    public MediaTypes.Clip synthesize(MediaTypes.SpeechRequest r, ProviderCredentials credentials) {
        String voice = r.voiceId();
        String lang = voice != null && voice.matches("[a-z]{2,3}-[A-Z]{2}-.*") ? voice.substring(0, voice.indexOf('-', 3))
                : "ta".equals(r.languageCode()) ? "ta-IN" : "en-IN";
        Map<String, Object> input = new LinkedHashMap<>();
        if (takesSsml(voice)) input.put("ssml", ssml(r.text()));
        else input.put("text", plain(r.text()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", input);
        body.put("voice", Map.of("languageCode", lang, "name", voice));
        body.put("audioConfig", Map.of("audioEncoding", "MP3"));
        JsonNode res = http.send(req("/v1/text:synthesize", Duration.ofMinutes(2), credentials)
                .header("Content-Type", "application/json").POST(http.json(body)).build(), code());
        String audio = res.path("audioContent").asText("");
        if (audio.isBlank()) throw new ProviderException(ProviderErrorKind.GENERATION_FAILED, "Google Cloud TTS returned no audio");
        return new MediaTypes.Clip(Base64.getDecoder().decode(audio), "audio/mpeg", null);
    }

    /** Text as SSML: escaped, with pauses as breaks. */
    static String ssml(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
        Matcher m = PAUSE_MARK.matcher(escaped);
        return "<speak>" + m.replaceAll("<break time=\"$1ms\"/>") + "</speak>";
    }

    /** Text without SSML: a pause becomes a comma or full stop. */
    static String plain(String text) {
        Matcher m = PAUSE_MARK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Long.parseLong(m.group(1)) >= 600 ? ". " : ", ");
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+([,.])", "$1").replaceAll("([,.])\\1+", "$1").strip();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
