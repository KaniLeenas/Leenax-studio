package com.leenax.studio.syllabus;

import com.leenax.studio.access.ActorContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Leases queued syllabus analyses from PostgreSQL, like the other workers, and runs each
 * as the person who started it. A worker may write versions but never approve them: the
 * database refuses decisions from background jobs (V11).
 */
@Component
public class AnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
    private static final int MAX_CONCURRENT = 2;

    /** Runs the notes and question jobs (kept apart so this package doesn't depend on notes). */
    public interface Runner {
        boolean handles(String kind);

        void process(UUID jobId);
    }

    private final AnalysisStore store;
    private final SyllabusService service;
    private final List<Runner> runners;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT, r -> {
        Thread t = new Thread(r, "analysis-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger inFlight = new AtomicInteger();

    public AnalysisWorker(AnalysisStore store, SyllabusService service, List<Runner> runners) {
        this.store = store;
        this.service = service;
        this.runners = runners;
    }

    @Scheduled(fixedDelayString = "${leenax.analysis.tick-ms:3000}", initialDelay = 11000)
    public void tick() {
        int free = MAX_CONCURRENT - inFlight.get();
        if (free <= 0) return;
        List<UUID> ids;
        try {
            ids = store.claim(free);
        } catch (RuntimeException e) {
            log.warn("Could not claim analyses: {}", e.getMessage());
            return;
        }
        for (UUID id : ids) {
            inFlight.incrementAndGet();
            executor.submit(() -> {
                try {
                    ActorContext.runForOwner(store.owner(id), () -> run(id));
                } catch (RuntimeException e) {
                    log.error("Analysis {} crashed", id, e);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    private void run(UUID id) {
        String kind = store.job(id).map(AnalysisStore.JobRow::kind).orElse("SYLLABUS");
        for (Runner r : runners) {
            if (r.handles(kind)) {
                r.process(id);
                return;
            }
        }
        service.process(id);
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(20, TimeUnit.SECONDS);
    }
}
