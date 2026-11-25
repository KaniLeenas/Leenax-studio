package com.leenax.studio.ai.provider;

import com.leenax.studio.ai.provider.MediaTypes.Clip;
import com.leenax.studio.ai.provider.MediaTypes.Sample;
import com.leenax.studio.ai.provider.MediaTypes.SpeechRequest;
import com.leenax.studio.ai.provider.MediaTypes.VoiceInfo;

import java.util.List;

public interface SpeechProvider {

    ProviderCode code();

    List<VoiceInfo> voices(ProviderCredentials credentials);

    /** Checks the credentials work; by default by listing voices. */
    default void verify(ProviderCredentials credentials) {
        voices(credentials);
    }

    Clip synthesize(SpeechRequest request, ProviderCredentials credentials);

    /** Longest text the model accepts in one request; longer scripts are split. */
    int maxChars(String model);

    /** How this provider writes a pause of the given length inside the text. */
    String pauseMarkup(double seconds);

    default boolean supportsCloning() {
        return false;
    }

    default VoiceInfo cloneVoice(String name, String description, List<Sample> samples, boolean removeBackgroundNoise, ProviderCredentials credentials) {
        throw new ProviderException(ProviderErrorKind.UNSUPPORTED_CAPABILITY, code().displayName() + " voice cloning is not supported");
    }

    default void deleteVoice(String voiceId, ProviderCredentials credentials) {
    }
}
