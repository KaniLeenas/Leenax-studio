package com.leenax.studio.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record Overview(int days, Instant from, Instant to, String timeZone, Costs costs, Publishing publishing,
                           Performance performance) {
    }

    // ---- costs (never added up across units: credits, USD and units are different money) ----

    public record UnitTotal(String unit, BigDecimal amount) {
    }

    public record DailyAmount(LocalDate date, String unit, BigDecimal amount) {
    }

    public record ProviderCost(String provider, String capability, String unit, BigDecimal amount, long calls) {
    }

    public record Costs(List<UnitTotal> totals, List<DailyAmount> daily, List<ProviderCost> byProvider,
                        long calls, long failed, long unknownCost) {
    }

    // ---- publishing ----

    public record PlatformCount(String platform, long published, long failed) {
    }

    public record DailyCount(LocalDate date, String platform, long count) {
    }

    public record Failure(UUID postId, String title, String platform, String accountName, String error, Instant at) {
    }

    public record Publishing(long posts, long published, long failed, long upcoming, List<PlatformCount> byPlatform,
                             List<DailyCount> daily, List<Failure> recentFailures) {
    }

    // ---- performance ----

    public record PlatformTotals(String platform, long posts, long withNumbers, Long views, Long likes, Long comments, Long shares) {
    }

    public record TopPost(UUID postId, UUID targetId, String title, String platform, String accountName, String url,
                          Long views, Long likes, Long comments, Long shares, Instant publishedAt, Instant collectedAt, String note) {
    }

    public record Performance(List<PlatformTotals> byPlatform, List<TopPost> posts, Instant lastCollectedAt) {
    }

    public record Snapshot(Instant collectedAt, Long views, Long likes, Long comments, Long shares) {
    }
}
