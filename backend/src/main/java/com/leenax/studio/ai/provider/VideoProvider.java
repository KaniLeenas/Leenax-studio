package com.leenax.studio.ai.provider;

import java.nio.file.Path;
import java.util.List;

/** Protocol adapter for one video provider. Implementations are stateless and thread-safe. */
public interface VideoProvider {

    ProviderCode code();

    /** Checks the key with a cheap, read-only call. Throws ProviderException on failure. */
    void verify(ProviderCredentials credentials);

    /** Current balance buckets, or an empty list when the provider has no balance API. */
    default List<BalanceSnapshot> balances(ProviderCredentials credentials) {
        return List.of();
    }

    /**
     * Starts a generation. Most providers return RUNNING with a job id;
     * a provider that answers synchronously may return SUCCEEDED directly.
     */
    VideoResult submit(VideoRequest request, ProviderCredentials credentials);

    VideoResult poll(String providerJobId, ProviderCredentials credentials);

    /** Best-effort cancel. Returns false if the provider has no cancel API. */
    default boolean cancel(String providerJobId, ProviderCredentials credentials) {
        return false;
    }

    /** Seconds to wait between status checks. */
    default int pollIntervalSeconds() {
        return 6;
    }

    /** Downloads a finished video into {@code destination}. */
    void download(VideoResult.Output output, ProviderCredentials credentials, Path destination);
}
