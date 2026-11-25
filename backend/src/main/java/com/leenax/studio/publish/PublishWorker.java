package com.leenax.studio.publish;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishes due posts. Targets are leased from PostgreSQL (FOR UPDATE SKIP
 * LOCKED) so a restart picks them up again; each run does one step and gives
 * the lease back, so status checks don't hold a thread.
 */
@Component
public class PublishWorker {

    private static final Logger log = LoggerFactory.getLogger(PublishWorker.class);

    static final String CLAIM_SQL = """
            UPDATE post_targets SET locked_until = now() + interval '3 hours'
            WHERE id IN (
                SELECT id FROM post_targets
                WHERE status IN ('SCHEDULED', 'UPLOADING', 'PROCESSING', 'AWAITING_USER')
                  AND run_at IS NOT NULL AND run_at <= now()
                  AND (locked_until IS NULL OR locked_until < now())
                ORDER BY run_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    private final JdbcTemplate jdbc;
    private final PublishProcessor processor;
    private final int concurrency;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();

    public PublishWorker(JdbcTemplate jdbc, PublishProcessor processor,
                         @Value("${leenax.publish.max-concurrent:2}") int concurrency) {
        this.jdbc = jdbc;
        this.processor = processor;
        this.concurrency = Math.max(1, concurrency);
        this.executor = Executors.newFixedThreadPool(this.concurrency, r -> {
            Thread t = new Thread(r, "publish-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(fixedDelayString = "${leenax.publish.tick-ms:5000}", initialDelay = 12000)
    public void tick() {
        int free = concurrency - inFlight.get();
        if (free <= 0) return;
        List<UUID> ids;
        try {
            ids = jdbc.queryForList(CLAIM_SQL, UUID.class, free);
        } catch (RuntimeException e) {
            log.warn("Could not claim posts to publish: {}", e.getMessage());
            return;
        }
        for (UUID id : ids) {
            inFlight.incrementAndGet();
            executor.submit(() -> {
                try {
                    // Run as the job's owner: what it creates belongs to them (row-level security, V9).
                    com.leenax.studio.access.ActorContext.runForOwner(owner(id), () -> processor.process(id));
                } catch (RuntimeException e) {
                    log.error("Publishing {} crashed", id, e);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    /** Who started this job; read as the system, before acting for them. */
    private java.util.UUID owner(java.util.UUID id) {
        try {
            return jdbc.queryForList("select created_by from posts where id = ?", java.util.UUID.class, id)
                    .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
