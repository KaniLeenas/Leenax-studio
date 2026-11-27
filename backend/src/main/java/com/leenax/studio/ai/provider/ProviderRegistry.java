package com.leenax.studio.ai.provider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Looks up adapters by provider and capability. New providers are added here and nowhere else in the business logic. */
public class ProviderRegistry {

    private final Map<ProviderCode, VideoProvider> video = new EnumMap<>(ProviderCode.class);
    private final Map<ProviderCode, TextProvider> text = new EnumMap<>(ProviderCode.class);
    private final Map<ProviderCode, SpeechProvider> speech = new EnumMap<>(ProviderCode.class);
    private final Map<ProviderCode, ImageProvider> images = new EnumMap<>(ProviderCode.class);
    private final Map<ProviderCode, TranscriptionProvider> transcription = new EnumMap<>(ProviderCode.class);
    private final Map<ProviderCode, SoundProvider> sound = new EnumMap<>(ProviderCode.class);
    private final ElevenLabsAdapter elevenLabs;

    public ProviderRegistry(List<VideoProvider> videoProviders, List<TextProvider> textProviders) {
        this(videoProviders, textProviders, List.of(), List.of(), List.of(), List.of(), null);
    }

    public ProviderRegistry(List<VideoProvider> videoProviders, List<TextProvider> textProviders,
                            List<SpeechProvider> speechProviders, List<ImageProvider> imageProviders,
                            List<TranscriptionProvider> transcriptionProviders, List<SoundProvider> soundProviders,
                            ElevenLabsAdapter elevenLabs) {
        videoProviders.forEach(p -> video.put(p.code(), p));
        textProviders.forEach(p -> text.put(p.code(), p));
        speechProviders.forEach(p -> speech.put(p.code(), p));
        imageProviders.forEach(p -> images.put(p.code(), p));
        transcriptionProviders.forEach(p -> transcription.put(p.code(), p));
        soundProviders.forEach(p -> sound.put(p.code(), p));
        this.elevenLabs = elevenLabs;
    }

    public static ProviderRegistry defaults(ProviderHttp http) {
        return defaults(http, "http://localhost:11434");
    }

    public static ProviderRegistry defaults(ProviderHttp http, String ollamaBaseUrl) {
        return defaults(http, ollamaBaseUrl, "http://freellmapi:3001");
    }

    public static ProviderRegistry defaults(ProviderHttp http, String ollamaBaseUrl, String freeLlmApiBaseUrl) {
        GeminiAdapter gemini = new GeminiAdapter(http);
        OpenAiAdapter openai = new OpenAiAdapter(http);
        OllamaAdapter ollama = new OllamaAdapter(http, ollamaBaseUrl);
        FreeLlmApiAdapter freeLlmApi = new FreeLlmApiAdapter(http, freeLlmApiBaseUrl);
        ElevenLabsAdapter eleven = new ElevenLabsAdapter(http);
        return new ProviderRegistry(
                List.of(new RunwayAdapter(http), gemini, new KlingAdapter(http), new LumaAdapter(http)),
                List.of(gemini, openai, new AnthropicAdapter(http), ollama, freeLlmApi),
                List.of(eleven, gemini, new GoogleCloudTtsAdapter(http)),
                List.of(openai, gemini),
                List.of(eleven, openai),
                List.of(eleven),
                eleven);
    }

    public Optional<VideoProvider> video(ProviderCode code) {
        return Optional.ofNullable(video.get(code));
    }

    public Optional<TextProvider> text(ProviderCode code) {
        return Optional.ofNullable(text.get(code));
    }

    public Optional<SpeechProvider> speech(ProviderCode code) {
        return Optional.ofNullable(speech.get(code));
    }

    public Optional<ImageProvider> image(ProviderCode code) {
        return Optional.ofNullable(images.get(code));
    }

    public Optional<TranscriptionProvider> transcription(ProviderCode code) {
        return Optional.ofNullable(transcription.get(code));
    }

    public Optional<SoundProvider> sound(ProviderCode code) {
        return Optional.ofNullable(sound.get(code));
    }

    /** Verifies a key with whichever adapter the provider has. */
    public void verify(ProviderCode code, ProviderCredentials credentials) {
        if (code == ProviderCode.ELEVENLABS && elevenLabs != null) {
            elevenLabs.verify(credentials);
        } else if (video.containsKey(code)) {
            video.get(code).verify(credentials);
        } else if (text.containsKey(code)) {
            text.get(code).verify(credentials);
        } else if (speech.containsKey(code)) {
            speech.get(code).verify(credentials);
        } else {
            throw new ProviderException(ProviderErrorKind.UNSUPPORTED_CAPABILITY, code.displayName() + " is not supported yet");
        }
    }

    /** Balance buckets from the provider's own API, or empty when it has none. */
    public List<BalanceSnapshot> balances(ProviderCode code, ProviderCredentials credentials) {
        if (code == ProviderCode.ELEVENLABS && elevenLabs != null) return elevenLabs.balances(credentials);
        VideoProvider v = video.get(code);
        return v == null ? List.of() : v.balances(credentials);
    }
}
