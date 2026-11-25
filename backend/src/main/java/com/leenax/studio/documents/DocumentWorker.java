package com.leenax.studio.documents;

import com.leenax.studio.access.ActorContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Leases queued document versions from PostgreSQL (same pattern as the other workers):
 * nothing is lost on restart. One at a time, because OCR uses all the CPU it gets.
 * Without the document service configured, uploads stay queued and are read once it is.
 */
@Component
public class DocumentWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentWorker.class);
    private static final int MAX_CONCURRENT = 1;

    private final DocumentStore store;
    private final DocumentProcessor processor;
    private final DocumentReader reader;
    private final JdbcTemplate jdbc;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT, r -> {
        Thread t = new Thread(r, "document-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger inFlight = new AtomicInteger();

    public DocumentWorker(DocumentStore store, DocumentProcessor processor, DocumentReader reader, JdbcTemplate jdbc) {
        this.store = store;
        this.processor = processor;
        this.reader = reader;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${leenax.documents.tick-ms:3000}", initialDelay = 9000)
    public void tick() {
        if (!reader.configured()) return;
        int free = MAX_CONCURRENT - inFlight.get();
        if (free <= 0) return;
        List<UUID> ids;
        try {
            ids = store.claim(free);
        } catch (RuntimeException e) {
            log.warn("Could not claim documents: {}", e.getMessage());
            return;
        }
        for (UUID id : ids) {
            inFlight.incrementAndGet();
            executor.submit(() -> {
                try {
                    // As the uploader: what the worker writes is attributed to them (row-level security, V9/V10).
                    ActorContext.runForOwner(owner(id), () -> processor.process(id));
                } catch (RuntimeException e) {
                    log.error("Document version {} crashed", id, e);
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

    private UUID owner(UUID id) {
        try {
            return jdbc.queryForList("select created_by from document_versions where id = ?", UUID.class, id)
                    .stream().filter(Objects::nonNull).findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
