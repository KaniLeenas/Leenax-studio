package com.leenax.studio.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    @Test
    void allowsTheCeilingPerMinuteThenRefusesUntilTheNextMinute() {
        RateLimitFilter f = new RateLimitFilter(3);
        assertTrue(f.allow("1.1.1.1", 100));
        assertTrue(f.allow("1.1.1.1", 100));
        assertTrue(f.allow("1.1.1.1", 100));
        assertFalse(f.allow("1.1.1.1", 100));
        assertTrue(f.allow("2.2.2.2", 100));     // other addresses unaffected
        assertTrue(f.allow("1.1.1.1", 101));     // new minute
    }

    @Test
    void onlyApiCallsAreCountedAndEventStreamsAreLeftAlone() {
        assertTrue(RateLimitFilter.limited("/api/v1/media"));
        assertFalse(RateLimitFilter.limited("/api/v1/ai/generation/events"));
        assertFalse(RateLimitFilter.limited("/actuator/health"));
        assertFalse(RateLimitFilter.limited(null));
    }
}
