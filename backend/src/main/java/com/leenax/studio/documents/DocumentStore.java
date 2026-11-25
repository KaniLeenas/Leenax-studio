package com.leenax.studio.documents;

import com.leenax.studio.documents.DocumentDtos.PageText;
import com.leenax.studio.documents.DocumentDtos.Status;
import com.leenax.studio.documents.DocumentDtos.VersionResponse;
import com.leenax.studio.documents.DocumentText.Chunk;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for course documents. Every statement runs on a connection that already says
 * who is acting (ActorDataSource), so PostgreSQL's row-level security decides what
 * each person sees: these queries never filter by user themselves.
 */
@Component
public class DocumentStore {

    public record DocRow(UUID id, UUID projectId, String title, Instant createdAt, Instant updatedAt, String createdBy) {
    }

    /** A version plus what the worker needs to read it. */
    public record VersionRow(VersionResponse view, UUID documentId, String storageKey) {
    }

    public record Candidate(UUID documentId, String title, UUID versionId, int versionNo, int pageNo, String text) {
    }

    static final String VERSION_COLUMNS = """
            v.id, v.version_no, v.original_filename, v.content_type, v.size_bytes, v.status, v.error, v.page_count,
            v.pages_read, v.ocr_pages, v.ocr_confidence, v.language, v.module_codes, v.detected_title, v.force_ocr,
            v.created_at, v.processed_at, vu.display_name, v.document_id, v.storage_key
            """;

    /** Leases due versions; same pattern as the render and generation workers. */
    static final String CLAIM_SQL = """
            UPDATE document_versions SET locked_until = now() + interval '45 minutes'
            WHERE id IN (
                SELECT id FROM document_versions
                WHERE status IN ('QUEUED', 'PROCESSING')
                  AND next_attempt_at <= now()
                  AND (locked_until IS NULL OR locked_until < now())
                ORDER BY next_attempt_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    private final JdbcTemplate jdbc;

    public DocumentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- reading -----------------------------------------------------------------------------------

    public List<DocRow> documents(UUID projectId) {
        return jdbc.query("""
                select d.id, d.project_id, d.title, d.created_at, d.updated_at, u.display_name
                  from documents d left join users u on u.id = d.created_by
                 where d.project_id = ? order by d.updated_at desc, d.title
                """, DocumentStore::doc, projectId);
    }

    public Optional<DocRow> document(UUID id) {
        return jdbc.query("""
                select d.id, d.project_id, d.title, d.created_at, d.updated_at, u.display_name
                  from documents d left join users u on u.id = d.created_by where d.id = ?
                """, DocumentStore::doc, id).stream().findFirst();
    }

    /** Every version in a project, newest first. */
    public List<VersionRow> projectVersions(UUID projectId) {
        return jdbc.query("select " + VERSION_COLUMNS + """
                  from document_versions v join documents d on d.id = v.document_id
                  left join users vu on vu.id = v.created_by
                 where d.project_id = ? order by v.version_no desc
                """, DocumentStore::version, projectId);
    }

    public List<VersionRow> versions(UUID documentId) {
        return jdbc.query("select " + VERSION_COLUMNS + """
                  from document_versions v left join users vu on vu.id = v.created_by
                 where v.document_id = ? order by v.version_no desc
                """, DocumentStore::version, documentId);
    }

    public Optional<VersionRow> version(UUID versionId) {
        return jdbc.query("select " + VERSION_COLUMNS + """
                  from document_versions v left join users vu on vu.id = v.created_by where v.id = ?
                """, DocumentStore::version, versionId).stream().findFirst();
    }

    /** A file already in this project with the same contents, as "title (version n)". */
    public Optional<String> sameFile(UUID projectId, String sha256) {
        return jdbc.query("""
                select d.title, v.version_no from document_versions v join documents d on d.id = v.document_id
                 where d.project_id = ? and v.sha256 = ? order by v.created_at limit 1
                """, (rs, i) -> "“" + rs.getString(1) + "” (version " + rs.getInt(2) + ")", projectId, sha256)
                .stream().findFirst();
    }

    public Optional<String> sha256(UUID versionId) {
        return jdbc.queryForList("select sha256 from document_versions where id = ?", String.class, versionId).stream().findFirst();
    }

    public List<PageText> pages(UUID versionId, int from, int limit) {
        return jdbc.query("""
                select page_no, method, ocr_confidence, text from document_pages
                 where version_id = ? and page_no >= ? order by page_no limit ?
                """, (rs, i) -> new PageText(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)),
                versionId, from, limit);
    }

    /**
     * Passages in the newest successfully read version of each document in the project that
     * contain a word starting with every one of the given words. Words are letters, marks and
     * digits only (see DocumentText), so they can't carry LIKE wildcards.
     */
    public List<Candidate> candidates(UUID projectId, List<String> words, int limit) {
        StringBuilder sql = new StringBuilder("""
                with latest as (
                    select distinct on (v.document_id) v.id, v.document_id, v.version_no
                      from document_versions v join documents d on d.id = v.document_id
                     where d.project_id = ? and v.status = 'READY'
                     order by v.document_id, v.version_no desc)
                select l.document_id, d.title, l.id, l.version_no, c.page_no, c.text
                  from document_chunks c
                  join latest l on l.id = c.version_id
                  join documents d on d.id = l.document_id
                 where true""");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        for (String w : words) {
            sql.append(" and exists (select 1 from unnest(c.terms) t where t like ?)");
            args.add(w + "%");
        }
        sql.append(" order by d.updated_at desc, c.ordinal limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getInt(4), rs.getInt(5), rs.getString(6)), args.toArray());
    }

    // ---- writing (as the person) -------------------------------------------------------------------

    /** A new document with its first version, in one transaction. */
    @Transactional
    public void createDocument(UUID documentId, UUID projectId, String title, NewVersion v) {
        jdbc.update("insert into documents (id, project_id, title) values (?, ?, ?)", documentId, projectId, title);
        insertVersion(documentId, 1, v);
    }

    public record NewVersion(UUID id, String filename, String contentType, long sizeBytes, String sha256, String storageKey) {
    }

    @Transactional
    public int addVersion(UUID documentId, NewVersion v) {
        Integer next = jdbc.queryForObject("select coalesce(max(version_no), 0) + 1 from document_versions where document_id = ?",
                Integer.class, documentId);
        int no = next == null ? 1 : next;
        insertVersion(documentId, no, v);
        jdbc.update("update documents set updated_at = now() where id = ?", documentId);
        return no;
    }

    private void insertVersion(UUID documentId, int no, NewVersion v) {
        jdbc.update("""
                insert into document_versions (id, document_id, version_no, original_filename, content_type, size_bytes, sha256, storage_key)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, v.id(), documentId, no, v.filename(), v.contentType(), v.sizeBytes(), v.sha256(), v.storageKey());
    }

    /** Rows changed; 0 when row-level security hid or blocked it. */
    public int rename(UUID id, String title) {
        return jdbc.update("update documents set title = ?, updated_at = now() where id = ?", title, id);
    }

    public int delete(UUID id) {
        return jdbc.update("delete from documents where id = ?", id);
    }

    /** Back in the queue, to be read again (optionally from the page images). */
    public int requeue(UUID versionId, boolean forceOcr) {
        return jdbc.update("""
                update document_versions set status = 'QUEUED', force_ocr = ?, error = null, attempts = 0,
                       next_attempt_at = now(), locked_until = null
                 where id = ? and status in ('READY', 'FAILED')
                """, forceOcr, versionId);
    }

    // ---- writing (as the worker) -------------------------------------------------------------------

    public List<UUID> claim(int max) {
        return jdbc.queryForList(CLAIM_SQL, UUID.class, max);
    }

    public int markProcessing(UUID versionId) {
        return jdbc.update("update document_versions set status = 'PROCESSING', attempts = attempts + 1 where id = ?", versionId);
    }

    public int attempts(UUID versionId) {
        Integer n = jdbc.queryForObject("select attempts from document_versions where id = ?", Integer.class, versionId);
        return n == null ? 0 : n;
    }

    /** Replaces the version's pages and passages and marks it read, in one transaction. */
    @Transactional
    public void saveResult(UUID versionId, DocumentReader.Result r, List<Chunk> chunks) {
        jdbc.update("delete from document_chunks where version_id = ?", versionId);
        jdbc.update("delete from document_pages where version_id = ?", versionId);
        List<DocumentReader.PageResult> pages = r.pages();
        jdbc.batchUpdate("insert into document_pages (version_id, page_no, method, ocr_confidence, text) values (?, ?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        DocumentReader.PageResult p = pages.get(i);
                        ps.setObject(1, versionId);
                        ps.setInt(2, p.page());
                        ps.setString(3, "OCR".equals(p.method()) ? "OCR" : "TEXT");
                        ps.setBigDecimal(4, p.confidence() == null ? null : BigDecimal.valueOf(p.confidence()));
                        ps.setString(5, p.text() == null ? "" : stripNul(p.text()));
                    }

                    @Override
                    public int getBatchSize() {
                        return pages.size();
                    }
                });
        jdbc.batchUpdate("insert into document_chunks (version_id, page_no, ordinal, text, terms) values (?, ?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Chunk c = chunks.get(i);
                        String text = stripNul(c.text());
                        ps.setObject(1, versionId);
                        ps.setInt(2, c.pageNo());
                        ps.setInt(3, c.ordinal());
                        ps.setString(4, text);
                        ps.setArray(5, ps.getConnection().createArrayOf("text", DocumentText.terms(text)));
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
        Double conf = pages.stream().filter(p -> p.confidence() != null).mapToDouble(DocumentReader.PageResult::confidence)
                .average().stream().boxed().findFirst().orElse(null);
        String[] codes = r.moduleCodes() == null ? new String[0] : r.moduleCodes().stream().limit(5).toArray(String[]::new);
        jdbc.update("""
                update document_versions set status = 'READY', error = null, page_count = ?, pages_read = ?, ocr_pages = ?,
                       ocr_confidence = ?, language = ?, module_codes = ?::text[], detected_title = ?, processed_at = now(),
                       locked_until = null
                 where id = ?
                """, r.pageCount(), r.pagesRead(), r.ocrPages(),
                conf == null ? null : BigDecimal.valueOf(Math.round(conf * 10) / 10.0),
                language(r.language()), pgArray(codes), clip(r.title(), 200), versionId);
        jdbc.update("update documents set updated_at = now() where id = (select document_id from document_versions where id = ?)", versionId);
    }

    public void retryLater(UUID versionId, String error, int delaySeconds) {
        jdbc.update("""
                update document_versions set status = 'QUEUED', error = ?, locked_until = null,
                       next_attempt_at = now() + make_interval(secs => ?)
                 where id = ?
                """, clip(error, 500), delaySeconds, versionId);
    }

    public void fail(UUID versionId, String error) {
        jdbc.update("update document_versions set status = 'FAILED', error = ?, locked_until = null, processed_at = now() where id = ?",
                clip(error, 500), versionId);
    }

    // ---- mapping -----------------------------------------------------------------------------------

    static String language(String l) {
        return l != null && List.of("en", "ta", "mixed", "unknown").contains(l) ? l : "unknown";
    }

    /** A PostgreSQL array literal; the codes are letters and digits only, but quote them anyway. */
    static String pgArray(String[] items) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        s = stripNul(s).strip();
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** PostgreSQL text can't hold NUL characters. */
    static String stripNul(String s) {
        return s.indexOf('\0') < 0 ? s : s.replace("\0", "");
    }

    private static DocRow doc(ResultSet rs, int i) throws SQLException {
        return new DocRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5)), rs.getString(6));
    }

    private static VersionRow version(ResultSet rs, int i) throws SQLException {
        Array codes = rs.getArray(13);
        List<String> moduleCodes = codes == null ? List.of() : Arrays.asList((String[]) codes.getArray());
        VersionResponse view = new VersionResponse(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), Status.valueOf(rs.getString(6)), rs.getString(7), (Integer) rs.getObject(8), (Integer) rs.getObject(9),
                (Integer) rs.getObject(10), rs.getBigDecimal(11), rs.getString(12), moduleCodes, rs.getString(14), rs.getBoolean(15),
                instant(rs.getTimestamp(16)), instant(rs.getTimestamp(17)), rs.getString(18));
        return new VersionRow(view, rs.getObject(19, UUID.class), rs.getString(20));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
