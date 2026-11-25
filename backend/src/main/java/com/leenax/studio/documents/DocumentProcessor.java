package com.leenax.studio.documents;

import com.leenax.studio.documents.DocumentStore.VersionRow;
import com.leenax.studio.media.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Reads one claimed version: file from storage, text from the document service, pages and passages into the database. */
@Component
public class DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);
    static final int MAX_ATTEMPTS = 3;

    private final DocumentStore store;
    private final ObjectStorage storage;
    private final DocumentReader reader;
    private final long maxBytes;

    public DocumentProcessor(DocumentStore store, ObjectStorage storage, DocumentReader reader,
                             @Value("${leenax.documents.max-bytes:104857600}") long maxBytes) {
        this.store = store;
        this.storage = storage;
        this.reader = reader;
        this.maxBytes = maxBytes;
    }

    public void process(UUID versionId) {
        VersionRow v = store.version(versionId).orElse(null);
        if (v == null) return; // deleted meanwhile
        store.markProcessing(versionId);
        try {
            byte[] file = storage.read(v.storageKey(), maxBytes);
            DocumentReader.Result result = reader.read(file, v.view().originalFilename(), v.view().contentType(), v.view().forceOcr());
            List<DocumentText.PageText> pages = result.pages().stream()
                    .map(p -> new DocumentText.PageText(p.page(), p.text())).toList();
            store.saveResult(versionId, result, DocumentText.chunk(pages));
            log.info("Document version {} read: {} pages, {} by OCR", versionId, result.pagesRead(), result.ocrPages());
        } catch (DocumentReader.ReadFailure e) {
            settle(versionId, e.getMessage(), e.retryable());
        } catch (RuntimeException e) {
            log.error("Reading document version {} failed", versionId, e);
            settle(versionId, "Reading the document failed unexpectedly", true);
        }
    }

    /** Retries with a growing wait (1, then 5 minutes); after three tries it's marked failed. */
    private void settle(UUID versionId, String message, boolean retryable) {
        int attempts = store.attempts(versionId);
        if (retryable && attempts < MAX_ATTEMPTS) {
            store.retryLater(versionId, message + " — trying again shortly", retryDelaySeconds(attempts));
        } else {
            store.fail(versionId, message);
        }
    }

    static int retryDelaySeconds(int attempts) {
        return attempts <= 1 ? 60 : 300;
    }
}
