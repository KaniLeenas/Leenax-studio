package com.leenax.studio.publish;

import java.time.Duration;

/** What a publisher step achieved. */
public sealed interface Outcome {

    /** The post is live (or as live as the platform will make it; see warning). */
    record Published(String remoteId, String url, String warning) implements Outcome {
    }

    /**
     * Not finished: come back after {@code after} in the given status.
     * {@code after == null} means stop polling (waiting on the user).
     */
    record Wait(PostTarget.Status status, java.time.Duration after, String step, double progress) implements Outcome {
        public static Wait processing(Duration after, String step) {
            return new Wait(PostTarget.Status.PROCESSING, after, step, 0.9);
        }
    }
}
