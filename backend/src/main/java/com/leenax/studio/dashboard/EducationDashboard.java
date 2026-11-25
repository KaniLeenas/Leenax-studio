package com.leenax.studio.dashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module 02, the main dashboard (E10): numbers counted in the database, as far as the signed-in
 * person can see them (row-level security applies to every query), what is waiting for them,
 * running work and recent decisions. Nothing is estimated.
 */
@Component
public class EducationDashboard {

    static final String COUNTS = """
            with recursive
              approved_topics as (
                select v.content from resource_versions v join resources r on r.id = v.resource_id
                 where r.kind = 'TOPICS' and v.status = 'APPROVED'),
              topic(node) as (
                select x from approved_topics a, jsonb_array_elements(coalesce(a.content -> 'nodes', '[]'::jsonb)) x
                 where coalesce((x ->> 'selected')::boolean, true)
                union all
                select c from topic t, jsonb_array_elements(coalesce(t.node -> 'children', '[]'::jsonb)) c
                 where coalesce((c ->> 'selected')::boolean, true)),
              kinds as (
                select r.kind, count(*) as made,
                       count(*) filter (where exists (select 1 from resource_versions v where v.resource_id = r.id and v.status = 'APPROVED')) as approved
                  from resources r group by r.kind)
            select
              (select count(*) from projects where kind = 'EDUCATION' and status <> 'ARCHIVED'),
              (select count(*) from documents),
              (select count(distinct r.project_id) from resources r join resource_versions v on v.resource_id = r.id
                where r.kind = 'SYLLABUS' and v.status = 'APPROVED'),
              (select count(*) from topic),
              coalesce((select made from kinds where kind = 'NOTES'), 0),
              coalesce((select approved from kinds where kind = 'NOTES'), 0),
              (select coalesce(sum(jsonb_array_length(coalesce(n.content -> 'questions', '[]'::jsonb))), 0)
                 from resources r cross join lateral (select v.content from resource_versions v where v.resource_id = r.id
                                                       order by v.version_no desc limit 1) n
                where r.kind = 'MCQ'),
              coalesce((select made from kinds where kind = 'QA'), 0),
              coalesce((select approved from kinds where kind = 'QA'), 0),
              (select count(*) from resource_versions where status = 'IN_REVIEW'),
              coalesce((select approved from kinds where kind = 'SEO'), 0),
              coalesce((select made from kinds where kind = 'SLIDES'), 0),
              coalesce((select approved from kinds where kind = 'SLIDES'), 0),
              coalesce((select sum(made) from kinds where kind in ('LESSON_VIDEO', 'QUIZ_VIDEO')), 0),
              coalesce((select sum(approved) from kinds where kind in ('LESSON_VIDEO', 'QUIZ_VIDEO')), 0),
              (select count(*) from projects p cross join lateral leenax_youtube_publication_status(p.id) s where s.status = 'PUBLISHED'),
              (select count(*) from analysis_jobs where status in ('QUEUED', 'RUNNING'))
                + (select count(*) from render_jobs where status in ('QUEUED', 'RENDERING'))
                + (select count(*) from generation_jobs where status in ('QUEUED', 'PROCESSING')),
              coalesce((select made from kinds where kind = 'TOPIC_ANALYSIS'), 0),
              coalesce((select approved from kinds where kind = 'TOPIC_ANALYSIS'), 0)
            """;

    public record Counts(long subjectProjects, long documents, long analysedModules, long approvedTopics, long notes, long approvedNotes,
                         long questions, long qaReports, long approvedQaReports, long pendingApprovals, long approvedSeoReports,
                         long presentations, long approvedPresentations, long videos, long approvedVideos, long publishedVideos,
                         long activeJobs, long topicAnalyses, long approvedTopicAnalyses) {
    }

    /** What the signed-in person should do next. */
    static final String TASKS = """
            select 'REVIEW' as kind, r.project_id, p.name, r.id, r.kind, r.title, v.version_no, v.updated_at, u.display_name
              from resource_versions v join resources r on r.id = v.resource_id join projects p on p.id = r.project_id
              left join users u on u.id = v.created_by
             where v.status = 'IN_REVIEW' and leenax_can_review(r.project_id) and v.created_by is distinct from leenax_uid()
            union all
            select 'CHANGES', r.project_id, p.name, r.id, r.kind, r.title, v.version_no, v.updated_at, null
              from resource_versions v join resources r on r.id = v.resource_id join projects p on p.id = r.project_id
             where v.status = 'CHANGES_REQUESTED' and v.created_by = leenax_uid()
            union all
            select 'PUBLISH', r.project_id, p.name, r.id, r.kind, r.title, v.version_no, v.updated_at, null
              from resource_versions v join resources r on r.id = v.resource_id join projects p on p.id = r.project_id
             where v.status = 'APPROVED' and r.kind in ('LESSON_VIDEO', 'QUIZ_VIDEO')
               and (leenax_mode() = 'admin' or leenax_project_role(r.project_id) = 'OWNER')
               and exists (select 1 from video_renders vr join render_jobs j on j.id = vr.render_job_id
                            where vr.resource_id = r.id and vr.content_hash = v.content_hash and j.status = 'COMPLETED'
                              and j.validation ->> 'ok' = 'true' and j.output_media_id is not null)
               and not exists (select 1 from youtube_publications y where y.version_id = v.id)
            union all
            select 'FAILED_JOB', j.project_id, p.name, null, j.kind, coalesce(j.error, 'No reason was given'), null, j.finished_at, null
              from analysis_jobs j join projects p on p.id = j.project_id
             where j.status = 'FAILED' and j.created_by = leenax_uid() and j.finished_at > now() - interval '7 days'
               and not exists (select 1 from analysis_jobs k where k.project_id = j.project_id and k.kind = j.kind
                                and k.target is not distinct from j.target and k.created_at > j.created_at)
            order by 8 desc nulls last
            limit 30
            """;

    public record Task(String kind, UUID projectId, String projectName, UUID resourceId, String resourceKind, String title, Integer versionNo,
                       Instant since, String byName) {
    }

    static final String ACTIVE = """
            select 'AI' as kind, j.project_id, p.name, j.kind, j.status, null::numeric, j.created_at, j.target
              from analysis_jobs j join projects p on p.id = j.project_id where j.status in ('QUEUED', 'RUNNING')
            union all
            select 'RENDER', j.project_id, p.name, j.timeline_name, j.status, j.progress, j.created_at, vr.resource_id::text
              from render_jobs j left join projects p on p.id = j.project_id left join video_renders vr on vr.render_job_id = j.id
             where j.status in ('QUEUED', 'RENDERING')
            order by 7
            limit 20
            """;

    public record ActiveJob(String kind, UUID projectId, String projectName, String what, String status, Double progress, Instant startedAt,
                            String target) {
    }

    static final String ACTIVITY = """
            select e.created_at, e.action, r.kind, r.id, r.title, e.project_id, p.name, u.display_name, v.version_no, e.comment
              from review_events e join resources r on r.id = e.resource_id join projects p on p.id = e.project_id
              left join users u on u.id = e.actor_id left join resource_versions v on v.id = e.version_id
             where e.action <> 'CREATED'
             order by e.created_at desc
             limit 15
            """;

    public record Activity(Instant at, String action, String resourceKind, UUID resourceId, String title, UUID projectId, String projectName,
                           String byName, Integer versionNo, String comment) {
    }

    private final JdbcTemplate jdbc;

    public EducationDashboard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Counts counts() {
        return jdbc.queryForObject(COUNTS, (rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11), rs.getLong(12), rs.getLong(13),
                rs.getLong(14), rs.getLong(15), rs.getLong(16), rs.getLong(17), rs.getLong(18), rs.getLong(19)));
    }

    public List<Task> tasks() {
        return jdbc.query(TASKS, (rs, i) -> new Task(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
                rs.getString(5), rs.getString(6), (Integer) rs.getObject(7), instant(rs.getTimestamp(8)), rs.getString(9)));
    }

    public List<ActiveJob> active() {
        return jdbc.query(ACTIVE, (rs, i) -> new ActiveJob(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6) == null ? null : rs.getDouble(6), instant(rs.getTimestamp(7)), rs.getString(8)));
    }

    public List<Activity> activity() {
        return jdbc.query(ACTIVITY, (rs, i) -> activity(rs, i));
    }

    private static Activity activity(ResultSet rs, int i) throws SQLException {
        return new Activity(instant(rs.getTimestamp(1)), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), (Integer) rs.getObject(9), rs.getString(10));
    }

    public record Overview(Counts counts, List<Task> tasks, List<ActiveJob> active, List<Activity> activity, Map<String, String> notes) {
    }

    public Overview overview() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("scope", "Counted in the database, for the projects you can see.");
        return new Overview(counts(), tasks(), active(), activity(), notes);
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
