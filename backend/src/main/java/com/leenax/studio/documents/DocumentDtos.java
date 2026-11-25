package com.leenax.studio.documents;

import com.leenax.studio.documents.DocumentText.Snippet;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    public enum Status { QUEUED, PROCESSING, READY, FAILED }

    public record VersionResponse(UUID id, int versionNo, String originalFilename, String contentType, long sizeBytes,
                                  Status status, String error, Integer pageCount, Integer pagesRead, Integer ocrPages,
                                  BigDecimal ocrConfidence, String language, List<String> moduleCodes, String detectedTitle,
                                  boolean forceOcr, Instant createdAt, Instant processedAt, String uploadedBy) {
    }

    /**
     * Does the document look like it belongs to this subject? Compares module codes
     * found on its first pages with the project's module code.
     * MATCH, DIFFERENT, or UNKNOWN when either side has no code (or it isn't read yet).
     */
    public record SubjectCheck(String result, List<String> found, String projectCode) {

        public static SubjectCheck of(List<String> found, String projectCode) {
            List<String> codes = found == null ? List.of() : found;
            String mine = code(projectCode);
            if (codes.isEmpty() || mine == null) return new SubjectCheck("UNKNOWN", codes, projectCode);
            boolean match = codes.stream().map(SubjectCheck::code).anyMatch(mine::equals);
            return new SubjectCheck(match ? "MATCH" : "DIFFERENT", codes, projectCode);
        }

        static String code(String s) {
            if (s == null) return null;
            String c = s.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
            return c.isEmpty() ? null : c;
        }
    }

    public record DocumentResponse(UUID id, UUID projectId, String title, Instant createdAt, Instant updatedAt,
                                   String createdBy, int versionCount, VersionResponse latest, SubjectCheck subject) {
    }

    public record DocumentDetail(DocumentResponse document, List<VersionResponse> versions, boolean canEdit) {
    }

    /** serviceReady: whether this server has the document service set up (else uploads wait in the queue). */
    public record DocumentList(boolean serviceReady, boolean canEdit, List<DocumentResponse> documents) {
    }

    public record PageText(int pageNo, String method, BigDecimal ocrConfidence, String text) {
    }

    public record PagesResponse(UUID versionId, int versionNo, Integer pageCount, int from, List<PageText> pages, boolean more) {
    }

    public record SearchHit(UUID documentId, String title, UUID versionId, int versionNo, int pageNo, Snippet snippet) {
    }

    /** words: what was searched for, after normalising. more: there were more hits than shown. */
    public record SearchResponse(String query, List<String> words, List<SearchHit> hits, boolean more) {
    }

    public record RenameRequest(@NotBlank @Size(max = 200) String title) {
    }

    public record ReprocessRequest(boolean forceOcr) {
    }

    public record DownloadUrl(String url, Instant expiresAt) {
    }
}
