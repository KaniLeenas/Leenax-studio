package com.leenax.studio.ai.provider;

import com.leenax.studio.ai.provider.MediaTypes.Clip;

public interface SoundProvider {

    ProviderCode code();

    Clip soundEffect(String prompt, Double durationSeconds, boolean loop, ProviderCredentials credentials);
}
