package com.leenax.studio.analytics;

import com.leenax.studio.analytics.AnalyticsDtos.*;
import com.leenax.studio.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Read-only reports over usage_logs, post_targets and post_metrics. */
@Service
public class AnalyticsService {

    private final JdbcTemplate jdbc;

    public AnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static ZoneId zone(String tz) {
        if (tz == null || tz.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Unknown time zone");
        }
    }

    /** From the start of the day {@code days - 1} days ago (in the user's zone) until now. */
    static Instant start(int days, ZoneId zone, Instant now) {
        LocalDate first = now.atZone(zone).toLocalDate().minusDays(days - 1L);
        return first.atStartOfDay(zone).toInstant();
    }

    public Overview overview(int days, String tz) {
        if (days < 1 || days > 365) throw ApiException.badRequest("Choose between 1 and 365 days");
        ZoneId zone = zone(tz);
        Instant to = Instant.now();
        Instant from = start(days, zone, to);
        Timestamp f = Timestamp.from(from);
        String z = zone.getId();
        return new Overview(days, from, to, z, costs(f, z), publishing(f, z), performance(f));
    }

    private Costs costs(Timestamp from, String zone) {
        List<UnitTotal> totals = jdbc.query("""
                SELECT cost_unit, sum(cost) FROM usage_logs
                WHERE created_at >= ? AND cost IS NOT NULL AND cost_unit IS NOT NULL
                GROUP BY cost_unit ORDER BY cost_unit
                """, (rs, i) -> new UnitTotal(rs.getString(1), rs.getBigDecimal(2)), from);
        List<DailyAmount> daily = jdbc.query("""
                SELECT (created_at AT TIME ZONE ?)::date AS d, cost_unit, sum(cost) FROM usage_logs
                WHERE created_at >= ? AND cost IS NOT NULL AND cost_unit IS NOT NULL
                GROUP BY d, cost_unit ORDER BY d
                """, (rs, i) -> new DailyAmount(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getBigDecimal(3)), zone, from);
        List<ProviderCost> byProvider = jdbc.query("""
                SELECT provider, capability, cost_unit, coalesce(sum(cost), 0), count(*) FROM usage_logs
                WHERE created_at >= ?
                GROUP BY provider, capability, cost_unit ORDER BY provider, capability
                """, (rs, i) -> new ProviderCost(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getLong(5)), from);
        Long[] counts = jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE NOT success), count(*) FILTER (WHERE cost IS NULL AND success)
                FROM usage_logs WHERE created_at >= ?
                """, (rs, i) -> new Long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, from);
        return new Costs(totals, daily, byProvider, counts[0], counts[1], counts[2]);
    }

    private Publishing publishing(Timestamp from, String zone) {
        Long[] c = jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM posts WHERE created_at >= ?),
                  (SELECT count(*) FROM post_targets WHERE status = 'PUBLISHED' AND published_at >= ?),
                  (SELECT count(*) FROM post_targets WHERE status = 'FAILED' AND updated_at >= ?),
                  (SELECT count(*) FROM post_targets WHERE status = 'SCHEDULED' AND run_at > now())
                """, (rs, i) -> new Long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, from, from, from);
        List<PlatformCount> byPlatform = jdbc.query("""
                SELECT platform,
                       count(*) FILTER (WHERE status = 'PUBLISHED' AND published_at >= ?),
                       count(*) FILTER (WHERE status = 'FAILED' AND updated_at >= ?)
                FROM post_targets GROUP BY platform ORDER BY platform
                """, (rs, i) -> new PlatformCount(rs.getString(1), rs.getLong(2), rs.getLong(3)), from, from);
        List<DailyCount> daily = jdbc.query("""
                SELECT (published_at AT TIME ZONE ?)::date AS d, platform, count(*) FROM post_targets
                WHERE status = 'PUBLISHED' AND published_at >= ?
                GROUP BY d, platform ORDER BY d
                """, (rs, i) -> new DailyCount(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getLong(3)), zone, from);
        List<Failure> failures = jdbc.query("""
                SELECT p.id, p.title, t.platform, t.account_name, t.error_message, t.updated_at
                FROM post_targets t JOIN posts p ON p.id = t.post_id
                WHERE t.status = 'FAILED' AND t.updated_at >= ?
                ORDER BY t.updated_at DESC LIMIT 8
                """, (rs, i) -> new Failure(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), instant(rs, 6)), from);
        return new Publishing(c[0], c[1], c[2], c[3], byPlatform, daily, failures);
    }

    private Performance performance(Timestamp from) {
        String latest = """
                WITH latest AS (
                  SELECT DISTINCT ON (target_id) target_id, views, likes, comments, shares, collected_at
                  FROM post_metrics ORDER BY target_id, collected_at DESC)
                """;
        List<PlatformTotals> byPlatform = jdbc.query(latest + """
                SELECT t.platform, count(*), count(l.target_id), sum(l.views), sum(l.likes), sum(l.comments), sum(l.shares)
                FROM post_targets t LEFT JOIN latest l ON l.target_id = t.id
                WHERE t.status = 'PUBLISHED' AND t.published_at >= ?
                GROUP BY t.platform ORDER BY t.platform
                """, (rs, i) -> new PlatformTotals(rs.getString(1), rs.getLong(2), rs.getLong(3),
                lng(rs, 4), lng(rs, 5), lng(rs, 6), lng(rs, 7)), from);
        List<TopPost> posts = jdbc.query(latest + """
                SELECT p.id, t.id, p.title, t.platform, t.account_name, t.remote_url,
                       l.views, l.likes, l.comments, l.shares, t.published_at, l.collected_at, t.metrics_note
                FROM post_targets t JOIN posts p ON p.id = t.post_id LEFT JOIN latest l ON l.target_id = t.id
                WHERE t.status = 'PUBLISHED' AND t.published_at >= ?
                ORDER BY l.views DESC NULLS LAST, l.likes DESC NULLS LAST, t.published_at DESC
                LIMIT 50
                """, (rs, i) -> new TopPost(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), lng(rs, 7), lng(rs, 8), lng(rs, 9), lng(rs, 10),
                instant(rs, 11), instant(rs, 12), rs.getString(13)), from);
        Instant last = jdbc.queryForObject("SELECT max(collected_at) FROM post_metrics", (rs, i) -> instant(rs, 1));
        return new Performance(byPlatform, posts, last);
    }

    public List<Snapshot> history(UUID targetId) {
        return jdbc.query("""
                SELECT collected_at, views, likes, comments, shares FROM post_metrics
                WHERE target_id = ? ORDER BY collected_at LIMIT 400
                """, (rs, i) -> new Snapshot(instant(rs, 1), lng(rs, 2), lng(rs, 3), lng(rs, 4), lng(rs, 5)), targetId);
    }

    static Long lng(ResultSet rs, int col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    static Instant instant(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
