package com.leenax.studio.ai.provider;

import com.leenax.studio.ai.provider.MediaTypes.Transcript;

import java.nio.file.Path;

public interface TranscriptionProvider {

    ProviderCode code();

    /** Largest audio file accepted in one request, in bytes. */
    long maxBytes();

    Transcript transcribe(Path audio, String mimeType, String languageCode, ProviderCredentials credentials);
}
