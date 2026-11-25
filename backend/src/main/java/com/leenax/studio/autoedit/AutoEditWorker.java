package com.leenax.studio.autoedit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Runs automatic edits one at a time (they call several AI services and FFmpeg).
 * Jobs are leased from PostgreSQL, so a restart picks them up again.
 */
@Component
public class AutoEditWorker {

    private static final Logger log = LoggerFactory.getLogger(AutoEditWorker.class);

    static final String CLAIM_SQL = """
            UPDATE auto_edit_jobs SET locked_until = now() + interval '3 hours'
            WHERE id IN (
                SELECT id FROM auto_edit_jobs
                WHERE status IN ('QUEUED', 'RUNNING')
                  AND (locked_until IS NULL OR locked_until < now())
                ORDER BY created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    private final JdbcTemplate jdbc;
    private final AutoEditProcessor processor;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-edit-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger inFlight = new AtomicInteger();

    public AutoEditWorker(JdbcTemplate jdbc, AutoEditProcessor processor) {
        this.jdbc = jdbc;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${leenax.auto-edit.tick-ms:3000}", initialDelay = 9000)
    public void tick() {
        if (inFlight.get() > 0) return;
        List<UUID> ids;
        try {
            ids = jdbc.queryForList(CLAIM_SQL, UUID.class);
        } catch (RuntimeException e) {
            log.warn("Could not claim auto-edit jobs: {}", e.getMessage());
            return;
        }
        for (UUID id : ids) {
            inFlight.incrementAndGet();
            executor.submit(() -> {
                try {
                    // Run as the job's owner: what it creates belongs to them (row-level security, V9).
                    com.leenax.studio.access.ActorContext.runForOwner(owner(id), () -> processor.process(id));
                } catch (RuntimeException e) {
                    log.error("Auto edit {} crashed", id, e);
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
            return jdbc.queryForList("select created_by from auto_edit_jobs where id = ?", java.util.UUID.class, id)
                    .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
