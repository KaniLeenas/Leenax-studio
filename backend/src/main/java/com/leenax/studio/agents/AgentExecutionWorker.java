package com.leenax.studio.agents;

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

/** Durable PostgreSQL queue for agent runs; expired leases are recovered after a process restart. */
@Component
public class AgentExecutionWorker {
    private static final Logger log = LoggerFactory.getLogger(AgentExecutionWorker.class);
    private static final int CONCURRENCY = 2;
    private final AgentExecutionService executions;
    private final ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY, r -> {
        Thread t = new Thread(r, "agent-execution-worker"); t.setDaemon(true); return t;
    });
    private final AtomicInteger inFlight = new AtomicInteger();

    public AgentExecutionWorker(AgentExecutionService executions) { this.executions = executions; }

    @Scheduled(fixedDelayString = "${leenax.agents.tick-ms:2000}", initialDelay = 12000)
    public void tick() {
        int free = CONCURRENCY - inFlight.get();
        if (free <= 0) return;
        List<UUID> ids;
        try { ids = executions.claim(free); }
        catch (RuntimeException e) { log.warn("Could not claim agent runs: {}", e.getMessage()); return; }
        for (UUID id : ids) {
            inFlight.incrementAndGet();
            pool.submit(() -> {
                try { ActorContext.runForOwner(executions.owner(id), () -> executions.process(id)); }
                catch (RuntimeException e) { log.error("Agent run {} crashed", id, e); }
                finally { inFlight.decrementAndGet(); }
            });
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException { pool.shutdown(); pool.awaitTermination(20, TimeUnit.SECONDS); }
}
