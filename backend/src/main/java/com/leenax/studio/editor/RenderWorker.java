package com.leenax.studio.editor;

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

/** Leases due export jobs from PostgreSQL (same pattern as the generation worker): nothing is lost on restart. */
@Component
public class RenderWorker {

    private static final Logger log = LoggerFactory.getLogger(RenderWorker.class);
    private static final int MAX_CONCURRENT = 2;

    static final String CLAIM_SQL = """
            UPDATE render_jobs SET locked_until = now() + interval '45 minutes'
            WHERE id IN (
                SELECT id FROM render_jobs
                WHERE status IN ('QUEUED', 'RENDERING')
                  AND next_attempt_at <= now()
                  AND (locked_until IS NULL OR locked_until < now())
                ORDER BY next_attempt_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    private final JdbcTemplate jdbc;
    private final RenderProcessor processor;
    private final RendererClient renderer;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT, r -> {
        Thread t = new Thread(r, "render-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger inFlight = new AtomicInteger();

    public RenderWorker(JdbcTemplate jdbc, RenderProcessor processor, RendererClient renderer) {
        this.jdbc = jdbc;
        this.processor = processor;
        this.renderer = renderer;
    }

    @Scheduled(fixedDelayString = "${leenax.renderer.tick-ms:2000}", initialDelay = 7000)
    public void tick() {
        if (!renderer.configured()) return;
        int free = MAX_CONCURRENT - inFlight.get();
        if (free <= 0) return;
        List<UUID> ids;
        try {
            ids = jdbc.queryForList(CLAIM_SQL, UUID.class, free);
        } catch (RuntimeException e) {
            log.warn("Could not claim render jobs: {}", e.getMessage());
            return;
        }
        for (UUID id : ids) {
            inFlight.incrementAndGet();
            executor.submit(() -> {
                try {
                    // Run as the job's owner: what it creates belongs to them (row-level security, V9).
                    com.leenax.studio.access.ActorContext.runForOwner(owner(id), () -> processor.process(id));
                } catch (RuntimeException e) {
                    log.error("Render job {} crashed", id, e);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(20, TimeUnit.SECONDS);
    }

    /** Who started this job; read as the system, before acting for them. */
    private java.util.UUID owner(java.util.UUID id) {
        try {
            return jdbc.queryForList("select created_by from render_jobs where id = ?", java.util.UUID.class, id)
                    .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
