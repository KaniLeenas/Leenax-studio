package com.leenax.studio.ai.generation;

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
 * Background worker for AI generation.
 *
 * Every few seconds it leases due jobs with SELECT … FOR UPDATE SKIP LOCKED,
 * so a job is only ever handled by one thread, and a job whose worker died
 * (restart, crash) becomes available again when its lease expires. No queue
 * service is needed and nothing is lost on restart.
 */
@Component
public class GenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(GenerationWorker.class);

    static final String CLAIM_SQL = """
            UPDATE generation_jobs SET locked_until = now() + interval '15 minutes'
            WHERE id IN (
                SELECT id FROM generation_jobs
                WHERE status IN ('QUEUED', 'PROCESSING')
                  AND next_attempt_at <= now()
                  AND (locked_until IS NULL OR locked_until < now())
                ORDER BY next_attempt_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    private final JdbcTemplate jdbc;
    private final GenerationProcessor processor;
    private final int maxConcurrent;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();

    public GenerationWorker(JdbcTemplate jdbc, GenerationProcessor processor,
                            @Value("${leenax.generation.max-concurrent:4}") int maxConcurrent) {
        this.jdbc = jdbc;
        this.processor = processor;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.executor = Executors.newFixedThreadPool(this.maxConcurrent, r -> {
            Thread t = new Thread(r, "generation-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(fixedDelayString = "${leenax.generation.tick-ms:3000}", initialDelay = 5000)
    public void tick() {
        int free = maxConcurrent - inFlight.get();
        if (free <= 0) return;
        List<UUID> ids;
        try {
            ids = jdbc.queryForList(CLAIM_SQL, UUID.class, free);
        } catch (RuntimeException e) {
            log.warn("Could not claim generation jobs: {}", e.getMessage());
            return;
        }
        for (UUID id : ids) {
            inFlight.incrementAndGet();
            executor.submit(() -> {
                try {
                    // Run as the job's owner: what it creates belongs to them (row-level security, V9).
                    com.leenax.studio.access.ActorContext.runForOwner(owner(id), () -> processor.process(id));
                } catch (RuntimeException e) {
                    log.error("Generation job {} crashed", id, e);
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
            return jdbc.queryForList("select created_by from generation_jobs where id = ?", java.util.UUID.class, id)
                    .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
