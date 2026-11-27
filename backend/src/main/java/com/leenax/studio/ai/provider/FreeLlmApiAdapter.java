package com.leenax.studio.ai.provider;

/** OpenAI-compatible text adapter for the private FreeLLMAPI router. */
public final class FreeLlmApiAdapter extends OpenAiAdapter {
    public FreeLlmApiAdapter(ProviderHttp http, String baseUrl) {
        super(http, stripTrailingSlash(baseUrl));
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.FREE_LLM_API;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://freellmapi:3001";
        return value.replaceAll("/+$", "");
    }
}
