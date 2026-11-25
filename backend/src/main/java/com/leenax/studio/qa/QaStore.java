package com.leenax.studio.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.qa.Qa.CorrectionRow;
import com.leenax.studio.qa.Qa.IssueRow;
import com.leenax.studio.qa.Qa.RunRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for QA runs, issues and corrections (V13). Row-level security applies, and the
 * database keeps findings as they were found. Also the blocking rule, as review checks.
 */
@Component
public class QaStore implements Review.Gate {

    static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public QaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- runs --------------------------------------------------------------------------------------

    static final String RUN_SQL = """
            select r.id, r.resource_id, r.version_id, v.version_no, r.content_hash, r.mode, r.model, r.provider, r.second_model, r.writer_model,
                   r.independent, r.result::text, u.display_name, r.created_at
              from qa_runs r join resource_versions v on v.id = r.version_id left join users u on u.id = r.created_by
            """;

    public void insertRun(UUID id, UUID projectId, UUID resourceId, UUID versionId, String contentHash, UUID jobId, String mode, String model,
                          String provider, String secondModel, String writerModel, boolean independent, String resultJson) {
        jdbc.update("insert into qa_runs (id, project_id, resource_id, version_id, content_hash, job_id, mode, model, provider, second_model,"
                        + " writer_model, independent, result) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                id, projectId, resourceId, versionId, contentHash, jobId, mode, model, provider, secondModel, writerModel, independent, resultJson);
    }

    public List<RunRow> runs(UUID resourceId) {
        return jdbc.query(RUN_SQL + " where r.resource_id = ? order by r.created_at desc", QaStore::run, resourceId);
    }

    /** The newest full check (not recheck) of a resource. */
    public Optional<RunRow> latestCheck(UUID resourceId) {
        return jdbc.query(RUN_SQL + " where r.resource_id = ? and r.mode = 'CHECK' order by r.created_at desc limit 1", QaStore::run, resourceId)
                .stream().findFirst();
    }

    /** The newest full check of exactly this content (a draft can change after its check). */
    public Optional<RunRow> checkOfContent(UUID resourceId, String contentHash) {
        return jdbc.query(RUN_SQL + " where r.resource_id = ? and r.mode = 'CHECK' and r.content_hash = ? order by r.created_at desc limit 1",
                QaStore::run, resourceId, contentHash).stream().findFirst();
    }

    // ---- issues ------------------------------------------------------------------------------------

    static final String ISSUE_SQL = """
            select i.id, i.number, i.resource_id, i.run_id, i.found_in_version_id, v.version_no, i.item_ref, i.item_label, i.category,
                   i.type, i.severity, i.original, i.explanation, i.basis, i.evidence::text, i.suggestion, i.confirmations::text,
                   i.status, i.status_note, i.recheck::text, u.display_name, i.decided_at, i.created_at
              from qa_issues i join resource_versions v on v.id = i.found_in_version_id left join users u on u.id = i.decided_by
            """;

    public int nextNumber(UUID resourceId) {
        Integer n = jdbc.queryForObject("select coalesce(max(number), 0) + 1 from qa_issues where resource_id = ?", Integer.class, resourceId);
        return n == null ? 1 : n;
    }

    public void insertIssue(UUID id, UUID projectId, UUID resourceId, UUID runId, UUID versionId, int number, Qa.Finding f) {
        jdbc.update("insert into qa_issues (id, project_id, resource_id, run_id, found_in_version_id, number, item_ref, item_label, category,"
                        + " type, severity, original, explanation, basis, evidence, suggestion, confirmations)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb)",
                id, projectId, resourceId, runId, versionId, number, f.itemRef(), f.itemLabel(), f.category(), f.type(), f.severity(),
                f.original(), f.explanation(), f.basis(), write(f.evidence()), f.suggestion(), write(f.confirmations()));
    }

    public List<IssueRow> issues(UUID resourceId) {
        List<IssueRow> rows = jdbc.query(ISSUE_SQL + " where i.resource_id = ? order by i.number", QaStore::issue, resourceId);
        Map<UUID, CorrectionRow> latest = new HashMap<>();
        for (CorrectionRow c : jdbc.query(CORRECTION_SQL + " join qa_issues i on i.id = c.issue_id where i.resource_id = ? order by c.created_at",
                QaStore::correction, resourceId)) {
            latest.put(c.issueId(), c);
        }
        List<IssueRow> out = new ArrayList<>();
        for (IssueRow r : rows) out.add(r.withCorrection(latest.get(r.id())));
        return out;
    }

    public Optional<IssueRow> issue(UUID id) {
        return jdbc.query(ISSUE_SQL + " where i.id = ?", QaStore::issue, id).stream().findFirst().map(r -> r.withCorrection(
                jdbc.query(CORRECTION_SQL + " where c.issue_id = ? order by c.created_at desc limit 1", QaStore::correction, id)
                        .stream().findFirst().orElse(null)));
    }

    public UUID projectOfIssue(UUID id) {
        return jdbc.queryForList("select project_id from qa_issues where id = ?", UUID.class, id).stream().findFirst().orElse(null);
    }

    /** OPEN → CORRECTED, after an editor accepted a correction. */
    public int markCorrected(UUID id, String note) {
        return jdbc.update("update qa_issues set status = 'CORRECTED', status_note = ? where id = ? and status = 'OPEN'", note, id);
    }

    /** A recheck's result: RESOLVED when fixed, otherwise back to (or still) OPEN. */
    public int recheck(UUID id, boolean fixed, String recheckJson) {
        return fixed
                ? jdbc.update("update qa_issues set status = 'RESOLVED', recheck = ?::jsonb where id = ? and status in ('OPEN', 'CORRECTED')", recheckJson, id)
                : jdbc.update("update qa_issues set status = 'OPEN', recheck = ?::jsonb where id = ? and status in ('OPEN', 'CORRECTED')", recheckJson, id);
    }

    public int dismiss(UUID id, String reason) {
        return jdbc.update("update qa_issues set status = 'DISMISSED', status_note = ? where id = ? and status in ('OPEN', 'CORRECTED')", reason, id);
    }

    public int reopen(UUID id, String reason) {
        return jdbc.update("update qa_issues set status = 'OPEN', status_note = ? where id = ? and status in ('DISMISSED', 'RESOLVED')", reason, id);
    }

    public void confirmations(UUID id, String json) {
        jdbc.update("update qa_issues set confirmations = ?::jsonb where id = ?", json, id);
    }

    // ---- corrections -------------------------------------------------------------------------------

    static final String CORRECTION_SQL = """
            select c.id, c.issue_id, c.proposal::text, c.status, c.applied_version_id, c.note, u.display_name, c.decided_at, c.created_at
              from qa_corrections c left join users u on u.id = c.decided_by
            """;

    public void insertCorrection(UUID id, UUID projectId, UUID issueId, JsonNode proposal) {
        jdbc.update("insert into qa_corrections (id, project_id, issue_id, proposal) values (?, ?, ?, ?::jsonb)", id, projectId, issueId,
                write(proposal));
    }

    public Optional<CorrectionRow> correction(UUID id) {
        return jdbc.query(CORRECTION_SQL + " where c.id = ?", QaStore::correction, id).stream().findFirst();
    }

    public List<CorrectionRow> corrections(UUID resourceId) {
        return jdbc.query(CORRECTION_SQL + " join qa_issues i on i.id = c.issue_id where i.resource_id = ? order by c.created_at", QaStore::correction,
                resourceId);
    }

    public int decideCorrection(UUID id, String status, UUID appliedVersionId, String note) {
        return jdbc.update("update qa_corrections set status = ?, applied_version_id = ?, note = ? where id = ? and status = 'PROPOSED'",
                status, appliedVersionId, note, id);
    }

    // ---- the blocking rule -------------------------------------------------------------------------

    /** Open issues on a resource or, for a QA report, on what it covers: [severity, count]. */
    static final String OPEN_SQL = """
            select i.severity, count(*), string_agg(i.type || ' ' || coalesce(i.item_label, ''), '; ' order by i.number)
              from qa_issues i
             where i.status in ('OPEN', 'CORRECTED')
               and (i.resource_id = ? or (? and i.resource_id in (select l.depends_on_id from resource_links l where l.resource_id = ?)))
             group by i.severity
            """;

    public Map<String, Object[]> openBySeverity(UUID resourceId, boolean report) {
        Map<String, Object[]> out = new HashMap<>();
        jdbc.query(OPEN_SQL, (rs, i) -> out.put(rs.getString(1), new Object[]{rs.getInt(2), rs.getString(3)}), resourceId, report, resourceId);
        return out;
    }

    @Override
    public List<Check> checks(UUID projectId, UUID resourceId, Kind kind, String contentHash) {
        if (kind != Kind.NOTES && kind != Kind.MCQ && kind != Kind.QA) return List.of();
        List<Check> out = new ArrayList<>();
        Map<String, Object[]> open = openBySeverity(resourceId, kind == Kind.QA);
        Object[] critical = open.get("CRITICAL");
        if (critical != null) {
            out.add(Check.fail("qa", "Blocked by QA: " + critical[0] + " critical issue" + ((int) critical[0] == 1 ? "" : "s")
                    + " open (" + shorten(readable((String) critical[1]), 220) + "). Correct and recheck, or a reviewer dismisses with a reason."));
        }
        int major = open.containsKey("MAJOR") ? (int) open.get("MAJOR")[0] : 0, minor = open.containsKey("MINOR") ? (int) open.get("MINOR")[0] : 0;
        if (major + minor > 0) out.add(Check.warn("qa-open", "QA: " + count(major, "major") + (major > 0 && minor > 0 ? " and " : "")
                + count(minor, "minor") + " issue" + (major + minor == 1 ? "" : "s") + " open"));
        if (kind != Kind.QA) {
            Optional<RunRow> run = checkOfContent(resourceId, contentHash);
            if (run.isPresent()) {
                if (critical == null) out.add(Check.pass("qa-run", "Checked by QA with " + run.get().model()
                        + (run.get().independent() ? "" : " (the same model that wrote it, so not independent)")));
            } else {
                Optional<RunRow> last = latestCheck(resourceId);
                out.add(Check.warn("qa-run", last.map(r -> "QA last checked version " + r.versionNo() + " as it was then, not as it is now")
                        .orElse("Not checked by QA yet")));
            }
        }
        return out;
    }

    static String readable(String s) {
        if (s == null) return "";
        return s.replace("WRONG_KEY", "wrong answer key").replace("FACTUAL_ERROR", "factual error").replace("AMBIGUOUS", "ambiguous question")
                .replace("MISSING_TOPIC", "missing syllabus topic").replace("CONTRADICTION", "contradiction");
    }

    private static String count(int n, String what) {
        return n == 0 ? "" : n + " " + what;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---- mapping -----------------------------------------------------------------------------------

    private static RunRow run(ResultSet rs, int i) throws SQLException {
        return new RunRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getBoolean(11), read(rs.getString(12)),
                rs.getString(13), instant(rs.getTimestamp(14)));
    }

    private static IssueRow issue(ResultSet rs, int i) throws SQLException {
        return new IssueRow(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), read(rs.getString(15)), rs.getString(16),
                read(rs.getString(17)), rs.getString(18), rs.getString(19), read(rs.getString(20)), rs.getString(21),
                instant(rs.getTimestamp(22)), instant(rs.getTimestamp(23)), null);
    }

    private static CorrectionRow correction(ResultSet rs, int i) throws SQLException {
        return new CorrectionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), read(rs.getString(3)), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9)));
    }

    static JsonNode read(String s) {
        if (s == null) return null;
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("Stored QA data isn't JSON", e);
        }
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
