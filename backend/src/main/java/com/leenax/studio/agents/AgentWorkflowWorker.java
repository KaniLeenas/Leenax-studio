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

/** One-at-a-time workflow execution keeps the new orchestration workload predictable on small hosts. */
@Component
public class AgentWorkflowWorker {
    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowWorker.class);
    private final AgentWorkflowService workflows;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "agent-workflow-worker"); t.setDaemon(true); return t; });

    public AgentWorkflowWorker(AgentWorkflowService workflows) { this.workflows = workflows; }

    @Scheduled(fixedDelayString = "${leenax.agents.tick-ms:2000}", initialDelay = 15000)
    public void tick() {
        if (inFlight.get() > 0) return;
        List<UUID> ids;
        try { ids = workflows.claim(1); } catch (RuntimeException e) { log.warn("Could not claim agent workflows: {}", e.getMessage()); return; }
        for (UUID id : ids) {
            inFlight.incrementAndGet();
            executor.submit(() -> {
                try { ActorContext.runForOwner(workflows.owner(id), () -> workflows.process(id)); }
                catch (RuntimeException e) { log.error("Agent workflow {} crashed", id, e); }
                finally { inFlight.decrementAndGet(); }
            });
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException { executor.shutdown(); executor.awaitTermination(20, TimeUnit.SECONDS); }
}
