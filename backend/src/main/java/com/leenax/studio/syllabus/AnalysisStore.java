package com.leenax.studio.syllabus;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** SQL for syllabus analyses and the document text they read. Row-level security applies. */
@Component
public class AnalysisStore {

    public record JobRow(UUID id, UUID projectId, String status, String input, UUID resultVersionId, String error, int attempts,
                         Instant createdAt, Instant finishedAt, String createdBy, String kind, String target, String summary) {
    }

    /** A course document's newest successfully read version, with its size. */
    public record ReadyDoc(UUID documentId, String title, UUID versionId, int versionNo, int pages, long chars) {
    }

    static final String CLAIM_SQL = """
            UPDATE analysis_jobs SET locked_until = now() + interval '20 minutes'
            WHERE id IN (
                SELECT id FROM analysis_jobs
                WHERE status IN ('QUEUED', 'RUNNING')
                  AND next_attempt_at <= now()
                  AND (locked_until IS NULL OR locked_until < now())
                ORDER BY next_attempt_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    static final String READY_DOCS_SQL = """
            select distinct on (d.id) d.id, d.title, v.id, v.version_no,
                   (select count(*) from document_pages p where p.version_id = v.id),
                   (select coalesce(sum(length(p.text)), 0) from document_pages p where p.version_id = v.id)
              from documents d join document_versions v on v.document_id = d.id
             where d.project_id = ? and v.status = 'READY'
             order by d.id, v.version_no desc
            """;

    private final JdbcTemplate jdbc;

    public AnalysisStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReadyDoc> readyDocuments(UUID projectId) {
        return jdbc.query(READY_DOCS_SQL, (rs, i) -> new ReadyDoc(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                rs.getInt(4), rs.getInt(5), rs.getLong(6)), projectId);
    }

    /** Page number → text, for one document version. */
    public Map<Integer, String> pages(UUID versionId) {
        Map<Integer, String> out = new LinkedHashMap<>();
        jdbc.query("select page_no, text from document_pages where version_id = ? order by page_no",
                (rs, i) -> Map.entry(rs.getInt(1), rs.getString(2)), versionId).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public void insert(UUID id, UUID projectId, String inputJson) {
        insert(id, projectId, "SYLLABUS", "", inputJson);
    }

    public void insert(UUID id, UUID projectId, String kind, String target, String inputJson) {
        jdbc.update("insert into analysis_jobs (id, project_id, kind, target, input) values (?, ?, ?, ?, ?::jsonb)",
                id, projectId, kind, target, inputJson);
    }

    /** Jobs of a kind in a project that are waiting or running, and the last finished one per target. */
    public List<JobRow> recent(UUID projectId, String kind) {
        return jdbc.query("select distinct on (j.target) " + JOB_SQL.strip().substring("select ".length())
                + " where j.project_id = ? and j.kind = ? order by j.target, j.created_at desc", AnalysisStore::job, projectId, kind);
    }

    public void summary(UUID id, String summary) {
        jdbc.update("update analysis_jobs set summary = ? where id = ?", clip(summary), id);
    }

    public Optional<JobRow> job(UUID id) {
        return jdbc.query(JOB_SQL + " where j.id = ?", AnalysisStore::job, id).stream().findFirst();
    }

    public Optional<JobRow> latest(UUID projectId) {
        return jdbc.query(JOB_SQL + " where j.project_id = ? and j.kind = 'SYLLABUS' order by j.created_at desc limit 1", AnalysisStore::job, projectId)
                .stream().findFirst();
    }

    static final String JOB_SQL = """
            select j.id, j.project_id, j.status, j.input::text, j.result_version_id, j.error, j.attempts, j.created_at,
                   j.finished_at, u.display_name, j.kind, j.target, j.summary
              from analysis_jobs j left join users u on u.id = j.created_by
            """;

    public List<UUID> claim(int max) {
        return jdbc.queryForList(CLAIM_SQL, UUID.class, max);
    }

    public void running(UUID id) {
        jdbc.update("update analysis_jobs set status = 'RUNNING', attempts = attempts + 1 where id = ?", id);
    }

    public void succeeded(UUID id, UUID versionId) {
        jdbc.update("update analysis_jobs set status = 'SUCCEEDED', result_version_id = ?, error = null, locked_until = null, finished_at = now() where id = ?",
                versionId, id);
    }

    public void retryLater(UUID id, String error, int seconds) {
        jdbc.update("update analysis_jobs set status = 'QUEUED', error = ?, locked_until = null, next_attempt_at = now() + make_interval(secs => ?) where id = ?",
                clip(error), seconds, id);
    }

    public void failed(UUID id, String error) {
        jdbc.update("update analysis_jobs set status = 'FAILED', error = ?, locked_until = null, finished_at = now() where id = ?", clip(error), id);
    }

    public int cancel(UUID id) {
        return jdbc.update("update analysis_jobs set status = 'CANCELLED', locked_until = null, finished_at = now() where id = ? and status = 'QUEUED'", id);
    }

    public UUID owner(UUID id) {
        return jdbc.queryForList("select created_by from analysis_jobs where id = ?", UUID.class, id).stream().findFirst().orElse(null);
    }

    private static JobRow job(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new JobRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getInt(7), instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9)),
                rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
