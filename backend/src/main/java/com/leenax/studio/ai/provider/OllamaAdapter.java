package com.leenax.studio.ai.provider;

/** OpenAI-compatible text adapter for a self-hosted Ollama server. */
public final class OllamaAdapter extends OpenAiAdapter {
    public OllamaAdapter(ProviderHttp http, String baseUrl) {
        super(http, stripTrailingSlash(baseUrl));
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.OLLAMA;
    }

    @Override
    public java.util.List<String> listModels(ProviderCredentials credentials) {
        // Keep the free local account from offering Ollama-hosted cloud tags.
        return super.listModels(credentials).stream().filter(model -> !model.toLowerCase(java.util.Locale.ROOT).endsWith(":cloud")).toList();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:11434";
        return value.replaceAll("/+$", "");
    }
}
