package com.leenax.studio.ai.provider;

import com.leenax.studio.ai.provider.MediaTypes.Clip;
import com.leenax.studio.ai.provider.MediaTypes.ImageRequest;

import java.util.List;

public interface ImageProvider {

    ProviderCode code();

    List<Clip> generate(ImageRequest request, ProviderCredentials credentials);
}
