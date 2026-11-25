package com.leenax.studio.ai.provider;

import java.util.List;

/** Text model used for prompt enhancement (and later scripts, titles and captions). */
public interface TextProvider {

    ProviderCode code();

    void verify(ProviderCredentials credentials);

    /** Model ids the key can use, straight from the provider's model list. */
    List<String> listModels(ProviderCredentials credentials);

    String complete(String model, String systemPrompt, String userPrompt, ProviderCredentials credentials);

    /**
     * Same as {@link #complete} with pictures attached (JPEG/PNG frames). Used to describe footage.
     * The model must accept images; most current OpenAI, Claude and Gemini models do.
     */
    default String completeWithImages(String model, String systemPrompt, String userPrompt, java.util.List<MediaTypes.Sample> images,
                                      ProviderCredentials credentials) {
        throw new ProviderException(ProviderErrorKind.INVALID_REQUEST, code().displayName() + " can't look at pictures here");
    }
}
