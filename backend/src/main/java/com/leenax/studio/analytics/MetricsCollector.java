package com.leenax.studio.analytics;

import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.social.Counts;
import com.leenax.studio.social.MetaClient;
import com.leenax.studio.social.SocialAccountService;
import com.leenax.studio.social.SocialException;
import com.leenax.studio.social.SocialPlatform;
import com.leenax.studio.social.TikTokClient;
import com.leenax.studio.social.YouTubeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Reads views, likes, comments and shares of published posts from each
 * platform's official API, every six hours for posts from the last 90 days.
 * Each run stores one snapshot per post, so numbers can be compared over time.
 */
@Component
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);
    static final Duration EVERY = Duration.ofHours(6);
    static final Duration MANUAL_GAP = Duration.ofMinutes(10);
    static final int WINDOW_DAYS = 90;

    /** One published post on one account. */
    public record Item(UUID targetId, UUID accountId, SocialPlatform platform, String remoteId, String remoteUrl) {
    }

    private final JdbcTemplate jdbc;
    private final SocialAccountService accounts;
    private final YouTubeClient youtube;
    private final MetaClient meta;
    private final TikTokClient tiktok;
    private final ObjectMapper mapper;
    private final AtomicReference<Instant> lastManual = new AtomicReference<>(Instant.EPOCH);
    private final Object running = new Object();

    public MetricsCollector(JdbcTemplate jdbc, SocialAccountService accounts, YouTubeClient youtube, MetaClient meta,
                            TikTokClient tiktok, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.youtube = youtube;
        this.meta = meta;
        this.tiktok = tiktok;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${leenax.analytics.tick-ms:1800000}", initialDelay = 60000)
    public void tick() {
        try {
            collect(due(false));
        } catch (RuntimeException e) {
            log.warn("Collecting post numbers failed: {}", e.getMessage());
        }
    }

    /** "Refresh now" from the analytics page; at most once every ten minutes. */
    public int refreshNow() {
        Instant prev = lastManual.get();
        if (prev.plus(MANUAL_GAP).isAfter(Instant.now()) || !lastManual.compareAndSet(prev, Instant.now())) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Numbers were refreshed a few minutes ago. Try again in 10 minutes.");
        }
        return collect(due(true));
    }

    List<Item> due(boolean all) {
        return jdbc.query("""
                SELECT id, account_id, platform, remote_id, remote_url FROM post_targets
                WHERE status = 'PUBLISHED' AND remote_id IS NOT NULL AND account_id IS NOT NULL
                  AND published_at > now() - make_interval(days => ?)
                  AND (? OR metrics_checked_at IS NULL OR metrics_checked_at < now() - interval '6 hours')
                ORDER BY metrics_checked_at NULLS FIRST
                LIMIT 300
                """, (rs, i) -> new Item(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                SocialPlatform.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5)), WINDOW_DAYS, all);
    }

    /** Collects per account, so one account's trouble doesn't stop the others. Returns posts updated. */
    int collect(List<Item> items) {
        synchronized (running) {
            int n = 0;
            Map<UUID, List<Item>> byAccount = items.stream().collect(Collectors.groupingBy(Item::accountId, LinkedHashMap::new, Collectors.toList()));
            for (var e : byAccount.entrySet()) {
                List<Item> list = e.getValue();
                try {
                    String token = accounts.accessToken(e.getKey());
                    Map<UUID, Counts> got = fetch(list.get(0).platform(), token, list);
                    for (Item it : list) {
                        Counts c = got.get(it.targetId());
                        if (c == null || c.isEmpty()) {
                            note(it.targetId(), noteFor(it));
                        } else {
                            save(it, c);
                            n++;
                        }
                    }
                } catch (SocialException ex) {
                    String why = ex.kind() == SocialException.Kind.RECONNECT
                            ? "Couldn't read numbers: " + ex.getMessage()
                            : "Couldn't read numbers right now (" + ex.getMessage() + ")";
                    list.forEach(it -> note(it.targetId(), why));
                } catch (RuntimeException ex) {
                    log.warn("Reading numbers for account {} failed: {}", e.getKey(), ex.getMessage());
                }
            }
            return n;
        }
    }

    Map<UUID, Counts> fetch(SocialPlatform platform, String token, List<Item> list) {
        Map<UUID, Counts> out = new LinkedHashMap<>();
        switch (platform) {
            case YOUTUBE -> {
                Map<String, Counts> stats = youtube.statistics(token, list.stream().map(Item::remoteId).toList());
                list.forEach(it -> out.put(it.targetId(), stats.get(it.remoteId())));
            }
            case FACEBOOK -> list.forEach(it -> out.put(it.targetId(), meta.reelCounts(it.remoteId(), token)));
            case INSTAGRAM -> list.forEach(it -> out.put(it.targetId(), meta.igCounts(it.remoteId(), token)));
            case TIKTOK -> {
                // Only public posts have a video id TikTok's Display API can look up.
                List<Item> publicOnes = new ArrayList<>(list.stream().filter(it -> it.remoteUrl() != null).toList());
                if (!publicOnes.isEmpty()) {
                    Map<String, Counts> stats = tiktok.videoCounts(token, publicOnes.stream().map(Item::remoteId).toList());
                    publicOnes.forEach(it -> out.put(it.targetId(), stats.get(it.remoteId())));
                }
            }
        }
        return out;
    }

    static String noteFor(Item it) {
        return switch (it.platform()) {
            case TIKTOK -> it.remoteUrl() == null
                    ? "TikTok has no public video for this post yet, so there are no numbers."
                    : "TikTok returned no numbers. Reconnect TikTok to allow reading your videos (video.list).";
            case YOUTUBE -> "YouTube returned no numbers (the video may be deleted or hidden).";
            default -> "No numbers returned yet. Reconnect Facebook and Instagram to allow reading insights.";
        };
    }

    private void save(Item it, Counts c) {
        String details;
        try {
            details = mapper.writeValueAsString(c.details());
        } catch (Exception e) {
            details = "{}";
        }
        jdbc.update("""
                INSERT INTO post_metrics (target_id, platform, views, likes, comments, shares, saves, reach, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, it.targetId(), it.platform().name(), c.views(), c.likes(), c.comments(), c.shares(), c.saves(), c.reach(), details);
        jdbc.update("UPDATE post_targets SET metrics_checked_at = ?, metrics_note = NULL WHERE id = ?",
                Timestamp.from(Instant.now()), it.targetId());
    }

    private void note(UUID targetId, String note) {
        jdbc.update("UPDATE post_targets SET metrics_checked_at = ?, metrics_note = ? WHERE id = ?",
                Timestamp.from(Instant.now()), note.length() > 300 ? note.substring(0, 300) : note, targetId);
    }
}
