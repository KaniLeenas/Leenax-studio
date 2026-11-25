package com.leenax.studio.analytics;

import com.leenax.studio.social.SocialPlatform;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsLogicTest {

    @Test
    void rangesStartAtMidnightInTheOwnersTimeZone() {
        Instant now = Instant.parse("2026-11-19T15:00:00Z");   // 20:30 in Colombo
        assertEquals(Instant.parse("2026-11-18T18:30:00Z"), AnalyticsService.start(1, ZoneId.of("Asia/Colombo"), now));
        assertEquals(Instant.parse("2026-11-12T18:30:00Z"), AnalyticsService.start(7, ZoneId.of("Asia/Colombo"), now));
        assertEquals(Instant.parse("2026-11-19T00:00:00Z"), AnalyticsService.start(1, AnalyticsService.zone(null), now));
        assertThrows(RuntimeException.class, () -> AnalyticsService.zone("Mars/Base"));
    }

    @Test
    void explainsWhyAPostHasNoNumbers() {
        var privateTikTok = new MetricsCollector.Item(UUID.randomUUID(), UUID.randomUUID(), SocialPlatform.TIKTOK, "pub", null);
        assertTrue(MetricsCollector.noteFor(privateTikTok).contains("no public video"));
        var publicTikTok = new MetricsCollector.Item(UUID.randomUUID(), UUID.randomUUID(), SocialPlatform.TIKTOK, "73", "https://t");
        assertTrue(MetricsCollector.noteFor(publicTikTok).contains("video.list"));
        var ig = new MetricsCollector.Item(UUID.randomUUID(), UUID.randomUUID(), SocialPlatform.INSTAGRAM, "1", "u");
        assertTrue(MetricsCollector.noteFor(ig).contains("Reconnect"));
    }
}
