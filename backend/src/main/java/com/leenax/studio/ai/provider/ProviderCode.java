package com.leenax.studio.ai.provider;

import java.util.Set;

/** AI providers the studio can talk to. Adapters implement the protocol for each. */
public enum ProviderCode {
    RUNWAY("Runway", Set.of(Capability.VIDEO), true,
            "https://dev.runwayml.com", "API key from the Runway developer portal"),
    GOOGLE_GEMINI("Google Gemini", Set.of(Capability.VIDEO, Capability.TEXT, Capability.IMAGE, Capability.VOICE), false,
            "https://aistudio.google.com/apikey", "API key from Google AI Studio"),
    KLING("Kling AI", Set.of(Capability.VIDEO), true,
            "https://kling.ai/dev/api-key", "API key from the Kling developer console (not the legacy Access/Secret key pair)"),
    LUMA("Luma AI", Set.of(Capability.VIDEO), true,
            "https://lumalabs.ai/api/keys", "API key from the Luma API platform"),
    OPENAI("OpenAI", Set.of(Capability.TEXT, Capability.IMAGE, Capability.TRANSCRIPTION), false,
            "https://platform.openai.com/api-keys", "Secret key from the OpenAI platform"),
    ANTHROPIC("Anthropic", Set.of(Capability.TEXT), false,
            "https://console.anthropic.com/settings/keys", "API key from the Claude Console"),
    ELEVENLABS("ElevenLabs", Set.of(Capability.VOICE, Capability.TRANSCRIPTION, Capability.SOUND), true,
            "https://elevenlabs.io/app/settings/api-keys", "API key from ElevenLabs (Developers → API keys)"),
    GOOGLE_CLOUD_TTS("Google Cloud Text-to-Speech", Set.of(Capability.VOICE), false,
            "https://console.cloud.google.com/iam-admin/serviceaccounts",
            "A service account key file (JSON) from a Google Cloud project with the Text-to-Speech API turned on; paste the whole file. An API key also works if your project allows it");

    public enum Capability { VIDEO, TEXT, IMAGE, VOICE, TRANSCRIPTION, SOUND }

    private final String displayName;
    private final Set<Capability> capabilities;
    private final boolean balanceApi;
    private final String keyUrl;
    private final String keyHelp;

    ProviderCode(String displayName, Set<Capability> capabilities, boolean balanceApi, String keyUrl, String keyHelp) {
        this.displayName = displayName;
        this.capabilities = capabilities;
        this.balanceApi = balanceApi;
        this.keyUrl = keyUrl;
        this.keyHelp = keyHelp;
    }

    public String displayName() { return displayName; }
    public Set<Capability> capabilities() { return capabilities; }
    /** Whether the provider exposes an official API for reading the remaining balance. */
    public boolean hasBalanceApi() { return balanceApi; }
    public String keyUrl() { return keyUrl; }
    public String keyHelp() { return keyHelp; }
}
