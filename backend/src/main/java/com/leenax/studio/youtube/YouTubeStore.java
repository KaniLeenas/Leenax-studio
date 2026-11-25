package com.leenax.studio.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for YouTube publishing and analytics (V17). */
@Component
public class YouTubeStore {

    static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public YouTubeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static JsonNode json(String s) {
        try {
            return s == null ? null : JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- publications ---------------------------------------------------------------------------

    public record Publication(UUID id, UUID resourceId, UUID versionId, String topicId, UUID postId, UUID postTargetId, UUID accountId,
                              String title, String createdByName, Instant createdAt, String status, String videoId, String url,
                              String error, Instant publishedAt, Instant runAt, JsonNode settings, Instant authorisedAt,
                              String authorisedByName, String statement, boolean rightsConfirmed, boolean publicConfirmed) {
    }

    static final String PUBLICATIONS = """
            select y.id, y.resource_id, y.version_id, y.topic_id, y.post_id, y.post_target_id, y.social_account_id, y.title,
                   u.display_name, y.created_at, s.status, s.remote_id, s.remote_url, s.error_message, s.published_at, s.run_at,
                   s.settings::text, a.authorised_at, au.display_name, a.statement, a.rights_confirmed, a.public_confirmed
              from youtube_publications y
              left join users u on u.id = y.created_by
              left join leenax_youtube_publication_status(?) s on s.publication_id = y.id
              left join publish_authorisations a on a.id = y.authorisation_id
              left join users au on au.id = a.authorised_by
             where y.project_id = ?
             order by y.created_at desc
             limit 100
            """;

    public List<Publication> publications(UUID projectId) {
        return jdbc.query(PUBLICATIONS, YouTubeStore::publication, projectId, projectId);
    }

    private static Publication publication(ResultSet rs, int i) throws SQLException {
        return new Publication(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
                instant(rs.getTimestamp(10)), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
                instant(rs.getTimestamp(15)), instant(rs.getTimestamp(16)), json(rs.getString(17)), instant(rs.getTimestamp(18)),
                rs.getString(19), rs.getString(20), rs.getBoolean(21), rs.getBoolean(22));
    }

    static final String ADD_PUBLICATION = """
            insert into youtube_publications (id, project_id, resource_id, version_id, topic_id, post_id, post_target_id,
                                              authorisation_id, social_account_id, title)
            select ?, ?, ?, ?, ?, t.post_id, t.id, a.id, t.account_id, ?
              from post_targets t join publish_authorisations a on a.post_target_id = t.id
             where t.id = ?
            """;

    public void addPublication(UUID id, UUID projectId, UUID resourceId, UUID versionId, String topicId, String title, UUID targetId) {
        int n = jdbc.update(ADD_PUBLICATION, id, projectId, resourceId, versionId, topicId, title, targetId);
        if (n != 1) throw new IllegalStateException("The post was not authorised");
    }

    /** Published videos of this project on a channel: {publication id, YouTube video id, title, resource id, version id, topic id}. */
    public record Published(UUID publicationId, String videoId, String title, UUID resourceId, UUID versionId, String topicId, UUID accountId,
                            Instant publishedAt) {
    }

    static final String PUBLISHED = """
            select y.id, s.remote_id, y.title, y.resource_id, y.version_id, y.topic_id, y.social_account_id, s.published_at
              from youtube_publications y
              join leenax_youtube_publication_status(?) s on s.publication_id = y.id
             where y.project_id = ? and s.status = 'PUBLISHED' and s.remote_id is not null
             order by s.published_at desc nulls last
            """;

    public List<Published> published(UUID projectId) {
        return jdbc.query(PUBLISHED, (rs, i) -> new Published(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, UUID.class),
                instant(rs.getTimestamp(8))), projectId, projectId);
    }

    /** The document a render job drew (the exact timeline of the checked file). */
    public Optional<JsonNode> renderDocument(UUID renderJobId) {
        return jdbc.query("select document::text from render_jobs where id = ?", (rs, i) -> json(rs.getString(1)), renderJobId)
                .stream().findFirst();
    }

    // ---- analytics snapshots --------------------------------------------------------------------

    public record Snapshot(UUID id, UUID projectId, UUID accountId, String kind, String videoId, LocalDate startDate, LocalDate endDate,
                           JsonNode data, Instant fetchedAt, String createdByName) {
    }

    static final String ADD_SNAPSHOT = """
            insert into youtube_analytics_snapshots (id, project_id, social_account_id, kind, video_id, start_date, end_date, data, fetched_at)
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

    public void addSnapshot(UUID id, UUID projectId, UUID accountId, String kind, String videoId, LocalDate start, LocalDate end, String data,
                            Instant fetchedAt) {
        jdbc.update(ADD_SNAPSHOT, id, projectId, accountId, kind, videoId, Date.valueOf(start), Date.valueOf(end), data, ts(fetchedAt));
    }

    /** The newest set of snapshots (taken together) for a project, channel and exact period. */
    static final String LATEST_SET = """
            select s.id, s.project_id, s.social_account_id, s.kind, s.video_id, s.start_date, s.end_date, s.data::text, s.fetched_at,
                   u.display_name
              from youtube_analytics_snapshots s left join users u on u.id = s.created_by
             where s.project_id = ? and s.social_account_id = ? and s.start_date = ? and s.end_date = ?
               and s.fetched_at = (select max(fetched_at) from youtube_analytics_snapshots
                                    where project_id = ? and social_account_id = ? and start_date = ? and end_date = ?)
             order by s.kind, s.video_id
            """;

    public List<Snapshot> latestSet(UUID projectId, UUID accountId, LocalDate from, LocalDate to) {
        Date f = Date.valueOf(from), t = Date.valueOf(to);
        return jdbc.query(LATEST_SET, YouTubeStore::snapshot, projectId, accountId, f, t, projectId, accountId, f, t);
    }

    /** A set taken at one time (all its kinds and videos). */
    public List<Snapshot> set(UUID projectId, UUID accountId, Instant fetchedAt) {
        return jdbc.query("""
                select s.id, s.project_id, s.social_account_id, s.kind, s.video_id, s.start_date, s.end_date, s.data::text, s.fetched_at,
                       u.display_name
                  from youtube_analytics_snapshots s left join users u on u.id = s.created_by
                 where s.project_id = ? and s.social_account_id = ? and s.fetched_at = ?
                 order by s.kind, s.video_id
                """, YouTubeStore::snapshot, projectId, accountId, ts(fetchedAt));
    }

    public record SetSummary(UUID accountId, LocalDate startDate, LocalDate endDate, Instant fetchedAt, String createdByName, int parts) {
    }

    /** Snapshot sets of a project, newest first, optionally overlapping a period. */
    static final String SETS = """
            select s.social_account_id, s.start_date, s.end_date, s.fetched_at, max(u.display_name), count(*)
              from youtube_analytics_snapshots s left join users u on u.id = s.created_by
             where s.project_id = ? and (?::date is null or s.end_date >= ?::date) and (?::date is null or s.start_date <= ?::date)
             group by s.social_account_id, s.start_date, s.end_date, s.fetched_at
             order by s.fetched_at desc, s.end_date desc
             limit 50
            """;

    public List<SetSummary> sets(UUID projectId, LocalDate from, LocalDate to) {
        Date f = from == null ? null : Date.valueOf(from), t = to == null ? null : Date.valueOf(to);
        return jdbc.query(SETS, (rs, i) -> new SetSummary(rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(), rs.getDate(3).toLocalDate(),
                instant(rs.getTimestamp(4)), rs.getString(5), rs.getInt(6)), projectId, f, f, t, t);
    }

    private static Snapshot snapshot(ResultSet rs, int i) throws SQLException {
        return new Snapshot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getString(5), rs.getDate(6).toLocalDate(), rs.getDate(7).toLocalDate(), json(rs.getString(8)), instant(rs.getTimestamp(9)),
                rs.getString(10));
    }

    // ---- reach reports (Reporting API) ----------------------------------------------------------

    public record ReportingJob(UUID accountId, String reportType, String jobId, Instant createdAt, Instant lastCheckedAt, String lastError) {
    }

    public Optional<ReportingJob> reportingJob(UUID accountId) {
        return jdbc.query("""
                select social_account_id, report_type, job_id, created_at, last_checked_at, last_error
                  from youtube_reporting_jobs where social_account_id = ?
                """, (rs, i) -> new ReportingJob(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), instant(rs.getTimestamp(4)),
                instant(rs.getTimestamp(5)), rs.getString(6)), accountId).stream().findFirst();
    }

    public List<ReportingJob> reportingJobs() {
        return jdbc.query("""
                select social_account_id, report_type, job_id, created_at, last_checked_at, last_error
                  from youtube_reporting_jobs order by created_at
                """, (rs, i) -> new ReportingJob(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), instant(rs.getTimestamp(4)),
                instant(rs.getTimestamp(5)), rs.getString(6)));
    }

    public void saveReportingJob(UUID accountId, String reportType, String jobId) {
        jdbc.update("""
                insert into youtube_reporting_jobs (social_account_id, report_type, job_id) values (?, ?, ?)
                on conflict (social_account_id) do update set report_type = excluded.report_type, job_id = excluded.job_id,
                    created_at = now(), last_checked_at = null, last_error = null
                """, accountId, reportType, jobId);
    }

    public void checked(UUID accountId, String error) {
        jdbc.update("update youtube_reporting_jobs set last_checked_at = now(), last_error = ? where social_account_id = ?", error, accountId);
    }

    public boolean haveReport(UUID accountId, String reportId) {
        Integer n = jdbc.queryForObject("select count(*) from youtube_reach_reports where social_account_id = ? and report_id = ?",
                Integer.class, accountId, reportId);
        return n != null && n > 0;
    }

    public record ReachRow(LocalDate day, String videoId, long impressions, Double ctr) {
    }

    /**
     * Stores one downloaded report, already summed to one row per day and video. A report for a day
     * replaces what an earlier report said about that day (YouTube sends backfills that way); the
     * caller runs this in one transaction.
     */
    public void saveReport(UUID accountId, String reportId, Instant start, Instant end, Instant created, List<ReachRow> rows) {
        java.util.Set<LocalDate> days = new java.util.TreeSet<>();
        rows.forEach(r -> days.add(r.day()));
        for (LocalDate d : days) {
            jdbc.update("delete from youtube_reach_daily where social_account_id = ? and day = ?", accountId, Date.valueOf(d));
        }
        for (ReachRow r : rows) {
            jdbc.update("""
                    insert into youtube_reach_daily (social_account_id, day, video_id, impressions, ctr, report_id) values (?, ?, ?, ?, ?, ?)
                    """, accountId, Date.valueOf(r.day()), r.videoId(), r.impressions(), r.ctr(), reportId);
        }
        jdbc.update("""
                insert into youtube_reach_reports (social_account_id, report_id, start_time, end_time, create_time, rows)
                values (?, ?, ?, ?, ?, ?) on conflict do nothing
                """, accountId, reportId, ts(start), ts(end), ts(created), rows.size());
    }

    public record Reach(String videoId, long impressions, Double ctr, LocalDate firstDay, LocalDate lastDay, int days) {
    }

    /** Impressions and click-through rate per video between two days (CTR weighted by impressions). */
    static final String REACH = """
            select video_id, sum(impressions),
                   case when sum(impressions) > 0 then sum(impressions * coalesce(ctr, 0)) / sum(impressions) end,
                   min(day), max(day), count(distinct day)
              from youtube_reach_daily
             where social_account_id = ? and day between ? and ?
             group by video_id
            """;

    public List<Reach> reach(UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.query(REACH, (rs, i) -> new Reach(rs.getString(1), rs.getLong(2), (Double) rs.getObject(3, Double.class),
                rs.getDate(4).toLocalDate(), rs.getDate(5).toLocalDate(), rs.getInt(6)), accountId, Date.valueOf(from), Date.valueOf(to));
    }

    public Optional<LocalDate> lastReachDay(UUID accountId) {
        return Optional.ofNullable(jdbc.queryForObject("select max(day) from youtube_reach_daily where social_account_id = ?",
                Date.class, accountId)).map(Date::toLocalDate);
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
