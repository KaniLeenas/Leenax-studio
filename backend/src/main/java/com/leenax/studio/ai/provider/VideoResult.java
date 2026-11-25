package com.leenax.studio.ai.provider;

import java.math.BigDecimal;

/**
 * Outcome of submitting or polling a generation.
 *
 * @param state       where the provider says the job is
 * @param progress    0..1 when the provider reports it
 * @param output      how to fetch the finished video (when SUCCEEDED)
 * @param cost        cost the provider reported (null if it doesn't report one)
 * @param costUnit    unit of cost, e.g. CREDITS or USD
 */
public record VideoResult(
        String providerJobId,
        State state,
        Double progress,
        Output output,
        BigDecimal cost,
        String costUnit,
        ProviderErrorKind errorKind,
        String errorMessage,
        Double outputSeconds) {

    public enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /** Either a URL to download (with or without the API key) or inline bytes. */
    public record Output(String url, boolean needsAuth, byte[] inlineBytes) {
        public static Output url(String url) { return new Output(url, false, null); }
        public static Output authenticatedUrl(String url) { return new Output(url, true, null); }
        public static Output inline(byte[] bytes) { return new Output(null, false, bytes); }
    }

    public static VideoResult running(String id, Double progress) {
        return new VideoResult(id, State.RUNNING, progress, null, null, null, null, null, null);
    }

    public static VideoResult running(String id, Double progress, BigDecimal estimatedCost, String unit) {
        return new VideoResult(id, State.RUNNING, progress, null, estimatedCost, unit, null, null, null);
    }

    public static VideoResult succeeded(String id, Output output, BigDecimal cost, String unit, Double seconds) {
        return new VideoResult(id, State.SUCCEEDED, 1.0, output, cost, unit, null, null, seconds);
    }

    public static VideoResult failed(String id, ProviderErrorKind kind, String message) {
        return new VideoResult(id, State.FAILED, null, null, null, null, kind, message, null);
    }

    public static VideoResult cancelled(String id) {
        return new VideoResult(id, State.CANCELLED, null, null, null, null, null, null, null);
    }
}
