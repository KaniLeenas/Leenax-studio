package com.leenax.studio.documents;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.documents.DocumentDtos.DocumentDetail;
import com.leenax.studio.documents.DocumentDtos.DocumentList;
import com.leenax.studio.documents.DocumentDtos.DocumentResponse;
import com.leenax.studio.documents.DocumentDtos.DownloadUrl;
import com.leenax.studio.documents.DocumentDtos.PageText;
import com.leenax.studio.documents.DocumentDtos.PagesResponse;
import com.leenax.studio.documents.DocumentDtos.SearchHit;
import com.leenax.studio.documents.DocumentDtos.SearchResponse;
import com.leenax.studio.documents.DocumentDtos.Status;
import com.leenax.studio.documents.DocumentDtos.SubjectCheck;
import com.leenax.studio.documents.DocumentDtos.VersionResponse;
import com.leenax.studio.documents.DocumentStore.Candidate;
import com.leenax.studio.documents.DocumentStore.DocRow;
import com.leenax.studio.documents.DocumentStore.NewVersion;
import com.leenax.studio.documents.DocumentStore.VersionRow;
import com.leenax.studio.media.storage.ObjectStorage;
import com.leenax.studio.media.storage.StorageProperties;
import com.leenax.studio.projects.Project;
import com.leenax.studio.projects.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final String EDIT_MESSAGE = "Only the project's owners and editors can change its documents";
    static final int PAGES_PER_REQUEST = 20;
    static final int SEARCH_CANDIDATES = 300;
    static final int SEARCH_RESULTS = 40;

    private final DocumentStore store;
    private final ProjectService projects;
    private final ObjectStorage storage;
    private final StorageProperties storageProps;
    private final DocumentReader reader;
    private final AuditService audit;
    private final long maxBytes;

    public DocumentService(DocumentStore store, ProjectService projects, ObjectStorage storage, StorageProperties storageProps,
                           DocumentReader reader, AuditService audit,
                           @Value("${leenax.documents.max-bytes:104857600}") long maxBytes) {
        this.store = store;
        this.projects = projects;
        this.storage = storage;
        this.storageProps = storageProps;
        this.reader = reader;
        this.audit = audit;
        this.maxBytes = maxBytes;
    }

    // ---- lists -------------------------------------------------------------------------------------

    public DocumentList list(UUID projectId) {
        Project project = projects.requireAccess(projectId, false, null);
        Map<UUID, List<VersionResponse>> byDoc = new LinkedHashMap<>();
        for (VersionRow v : store.projectVersions(projectId)) {
            byDoc.computeIfAbsent(v.documentId(), k -> new ArrayList<>()).add(v.view());
        }
        List<DocumentResponse> docs = store.documents(projectId).stream()
                .map(d -> response(d, byDoc.getOrDefault(d.id(), List.of()), project.getModuleCode()))
                .toList();
        return new DocumentList(reader.configured(), projects.canEdit(projectId), docs);
    }

    public DocumentDetail get(UUID id) {
        DocRow d = findDocument(id);
        Project project = projects.requireAccess(d.projectId(), false, null);
        List<VersionResponse> versions = store.versions(id).stream().map(VersionRow::view).toList();
        return new DocumentDetail(response(d, versions, project.getModuleCode()), versions, projects.canEdit(d.projectId()));
    }

    static DocumentResponse response(DocRow d, List<VersionResponse> newestFirst, String projectCode) {
        VersionResponse latest = newestFirst.isEmpty() ? null : newestFirst.get(0);
        List<String> codes = newestFirst.stream().filter(v -> v.status() == Status.READY).findFirst()
                .map(VersionResponse::moduleCodes).orElse(List.of());
        return new DocumentResponse(d.id(), d.projectId(), d.title(), d.createdAt(), d.updatedAt(), d.createdBy(),
                newestFirst.size(), latest, SubjectCheck.of(codes, projectCode));
    }

    // ---- uploads -----------------------------------------------------------------------------------

    /** A new document in the project. Refused if the same file is already in the project. */
    public DocumentDetail upload(UUID projectId, MultipartFile file, String title) {
        projects.requireAccess(projectId, true, EDIT_MESSAGE);
        Stored stored = store(file, projectId, null);
        UUID documentId = UUID.randomUUID();
        String name = title == null || title.isBlank() ? titleFrom(file.getOriginalFilename()) : DocumentStore.clip(title, 200);
        try {
            store.createDocument(documentId, projectId, name, stored.version());
        } catch (RuntimeException e) {
            deleteQuietly(stored.version().storageKey());
            throw e;
        }
        audit.record("DOCUMENT_UPLOADED", "document", documentId,
                Map.of("projectId", projectId, "type", stored.label(), "sizeBytes", stored.version().sizeBytes()));
        return get(documentId);
    }

    /** A newer version of an existing document; the older versions stay. */
    public DocumentDetail addVersion(UUID documentId, MultipartFile file) {
        DocRow d = findDocument(documentId);
        projects.requireAccess(d.projectId(), true, EDIT_MESSAGE);
        Stored stored = store(file, d.projectId(), documentId);
        int no;
        try {
            no = store.addVersion(documentId, stored.version());
        } catch (RuntimeException e) {
            deleteQuietly(stored.version().storageKey());
            throw e;
        }
        audit.record("DOCUMENT_VERSION_ADDED", "document", documentId, Map.of("version", no, "type", stored.label()));
        return get(documentId);
    }

    private record Stored(NewVersion version, String label) {
    }

    /** Checks type and size, refuses duplicates, and puts the file in private storage. */
    private Stored store(MultipartFile file, UUID projectId, UUID documentId) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("The file is empty");
        if (file.getSize() > maxBytes) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Documents can be up to " + (maxBytes / (1024 * 1024)) + " MB");
        }
        Path temp = null;
        try {
            temp = Files.createTempFile("leenax-doc-", ".bin");
            file.transferTo(temp);
            DocumentTypes.Type type = DocumentTypes.detect(header(temp), file.getOriginalFilename())
                    .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                            "This kind of file can't be added. Use " + DocumentTypes.ACCEPTED + "."));
            String sha = sha256(temp);
            if (documentId != null) {
                Optional<VersionRow> newest = store.versions(documentId).stream().findFirst();
                if (newest.isPresent() && sha.equals(newestSha(newest.get()))) {
                    throw new ApiException(ErrorCode.CONFLICT, "This is the same file as version " + newest.get().view().versionNo() + ".");
                }
            } else {
                store.sameFile(projectId, sha).ifPresent(where -> {
                    throw new ApiException(ErrorCode.CONFLICT, "This file is already in this project as " + where + ".");
                });
            }
            UUID versionId = UUID.randomUUID();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            String key = "documents/%d/%02d/%s.%s".formatted(today.getYear(), today.getMonthValue(), versionId, type.extension());
            storage.put(key, temp, type.contentType());
            return new Stored(new NewVersion(versionId, cleanFilename(file.getOriginalFilename(), type.extension()),
                    type.contentType(), Files.size(temp), sha, key), type.label());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not process the upload");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // temp dir is cleaned by the OS
                }
            }
        }
    }

    /** The newest version's checksum (kept out of the API response). */
    private String newestSha(VersionRow v) {
        return store.sha256(v.view().id()).orElse("");
    }

    // ---- changes -----------------------------------------------------------------------------------

    public DocumentDetail rename(UUID id, String title) {
        DocRow d = findDocument(id);
        projects.requireAccess(d.projectId(), true, EDIT_MESSAGE);
        if (store.rename(id, DocumentStore.clip(title, 200)) == 0) throw ApiException.notFound("Document");
        audit.record("DOCUMENT_RENAMED", "document", id, Map.of());
        return get(id);
    }

    /** Deletes the document, every version, and the stored files. */
    public void delete(UUID id) {
        DocRow d = findDocument(id);
        projects.requireAccess(d.projectId(), true, EDIT_MESSAGE);
        List<String> keys = store.versions(id).stream().map(VersionRow::storageKey).toList();
        if (store.delete(id) == 0) throw ApiException.notFound("Document");
        keys.forEach(this::deleteQuietly);
        audit.record("DOCUMENT_DELETED", "document", id, Map.of("projectId", d.projectId(), "versions", keys.size()));
    }

    /** Reads a version again; with forceOcr, from the page images even where the PDF has text. */
    public DocumentDetail reprocess(UUID versionId, boolean forceOcr) {
        VersionRow v = findVersion(versionId);
        DocRow d = findDocument(v.documentId());
        projects.requireAccess(d.projectId(), true, EDIT_MESSAGE);
        if (store.requeue(versionId, forceOcr) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "This version is already being read. Wait for it to finish.");
        }
        audit.record("DOCUMENT_REPROCESSED", "document", d.id(), Map.of("version", v.view().versionNo(), "forceOcr", forceOcr));
        return get(d.id());
    }

    // ---- reading -----------------------------------------------------------------------------------

    public PagesResponse pages(UUID versionId, int from) {
        VersionRow v = findVersion(versionId);
        projects.requireAccess(findDocument(v.documentId()).projectId(), false, null);
        int start = Math.max(1, from);
        List<PageText> page = store.pages(versionId, start, PAGES_PER_REQUEST + 1);
        boolean more = page.size() > PAGES_PER_REQUEST;
        return new PagesResponse(versionId, v.view().versionNo(), v.view().pageCount(), start,
                more ? page.subList(0, PAGES_PER_REQUEST) : page, more);
    }

    public DownloadUrl downloadUrl(UUID versionId) {
        VersionRow v = findVersion(versionId);
        projects.requireAccess(findDocument(v.documentId()).projectId(), false, null);
        String url = storage.signedUrl(v.storageKey(), v.view().originalFilename());
        return new DownloadUrl(url, Instant.now().plusSeconds(storageProps.signedUrlMinutes() * 60L));
    }

    public SearchResponse search(UUID projectId, String query) {
        projects.requireAccess(projectId, false, null);
        List<String> words = DocumentText.queryWords(query);
        if (words.isEmpty()) return new SearchResponse(query, words, List.of(), false);
        List<Candidate> found = store.candidates(projectId, words, SEARCH_CANDIDATES);
        return new SearchResponse(query, words, rank(found, words), found.size() > SEARCH_RESULTS);
    }

    static List<SearchHit> rank(List<Candidate> found, List<String> words) {
        record Scored(Candidate c, int score) {
        }
        return found.stream()
                .map(c -> new Scored(c, DocumentText.score(c.text(), words)))
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(s -> s.c().title())
                        .thenComparingInt(s -> s.c().pageNo()))
                .limit(SEARCH_RESULTS)
                .map(s -> new SearchHit(s.c().documentId(), s.c().title(), s.c().versionId(), s.c().versionNo(),
                        s.c().pageNo(), DocumentText.snippet(s.c().text(), words)))
                .toList();
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private DocRow findDocument(UUID id) {
        return store.document(id).orElseThrow(() -> ApiException.notFound("Document"));
    }

    private VersionRow findVersion(UUID id) {
        return store.version(id).orElseThrow(() -> ApiException.notFound("Document version"));
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete stored document {}: {}", key, e.getMessage());
        }
    }

    static String titleFrom(String filename) {
        String name = filename == null ? "" : filename.replaceAll(".*[/\\\\]", "");
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(0, dot);
        name = name.replaceAll("[_]+", " ").replaceAll("\\s+", " ").strip();
        return name.isEmpty() ? "Untitled document" : DocumentStore.clip(name, 200);
    }

    static String cleanFilename(String filename, String extension) {
        String name = filename == null ? "" : filename.replaceAll(".*[/\\\\]", "").replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.isEmpty()) name = "document." + extension;
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }

    private static byte[] header(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(DocumentTypes.HEADER_BYTES);
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
