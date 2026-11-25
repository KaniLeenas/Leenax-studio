package com.leenax.studio.social;

import java.util.Map;

/**
 * Performance numbers for one post as the platform reports them. A null field
 * means the platform did not give that number (not zero).
 */
public record Counts(Long views, Long likes, Long comments, Long shares, Long saves, Long reach, Map<String, Object> details) {

    public static Counts empty() {
        return new Counts(null, null, null, null, null, null, Map.of());
    }

    public boolean isEmpty() {
        return views == null && likes == null && comments == null && shares == null && saves == null && reach == null;
    }

    /** Fills gaps in this result from another source. */
    public Counts orElse(Counts other) {
        return new Counts(views != null ? views : other.views, likes != null ? likes : other.likes,
                comments != null ? comments : other.comments, shares != null ? shares : other.shares,
                saves != null ? saves : other.saves, reach != null ? reach : other.reach, details);
    }
}
