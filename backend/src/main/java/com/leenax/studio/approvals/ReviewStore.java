package com.leenax.studio.approvals;

import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.Review.Origin;
import com.leenax.studio.approvals.Review.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for the approval engine. Runs on connections that already say who is acting, so
 * row-level security and the V11 trigger apply to every statement.
 */
@Component
public class ReviewStore {

    /** topicId and level: set for notes (both) and question banks (topic only). */
    public record ResourceRow(UUID id, UUID projectId, Kind kind, String title, String topicId, String level, Instant createdAt,
                              Instant updatedAt) {
    }

    /** content is the JSON text; null in lists. */
    public record VersionRow(UUID id, UUID resourceId, int versionNo, Status status, Origin origin, String contentHash,
                             UUID basedOn, UUID dependsOn, UUID createdBy, String createdByName, Instant createdAt,
                             Instant updatedAt, String content) {
    }

    public record EventRow(UUID id, UUID versionId, int versionNo, String action, String previousStatus, String newStatus,
                           String comment, String details, UUID actorId, String actorName, Instant createdAt) {
    }

    static final String VERSION_COLUMNS = """
            v.id, v.resource_id, v.version_no, v.status, v.origin, v.content_hash, v.based_on_version_id,
            v.depends_on_version_id, v.created_by, u.display_name, v.created_at, v.updated_at
            """;

    /** Every resource that depends on the given one, directly or through others. */
    static final String DEPENDENTS_SQL = """
            with recursive deps(id) as (
                select l.resource_id from resource_links l where l.depends_on_id = ?
                union
                select l.resource_id from resource_links l join deps d on l.depends_on_id = d.id)
            select id from deps
            """;

    /** Versions of dependents that were built on what is about to change. */
    static final String OUTDATE_SQL = """
            with old as (
                select id, status from resource_versions
                 where resource_id = ? and status in ('DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'APPROVED')
                 for update)
            update resource_versions v set status = 'OUTDATED' from old where v.id = old.id
            returning v.id, old.status
            """;

    private final JdbcTemplate jdbc;

    public ReviewStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- resources ---------------------------------------------------------------------------------

    static final String RESOURCE_COLUMNS = "select id, project_id, kind, title, topic_id, level, created_at, updated_at from resources";

    /** The project's syllabus or topic list (one of each). */
    public Optional<ResourceRow> resource(UUID projectId, Kind kind) {
        return resource(projectId, kind, null, null);
    }

    /** A resource by what it is: kind, and for notes and banks the topic (and level). */
    public Optional<ResourceRow> resource(UUID projectId, Kind kind, String topicId, String level) {
        return jdbc.query(RESOURCE_COLUMNS + " where project_id = ? and kind = ? and coalesce(topic_id, '') = ? and coalesce(level, '') = ?",
                ReviewStore::resource, projectId, kind.name(), topicId == null ? "" : topicId, level == null ? "" : level)
                .stream().findFirst();
    }

    public Optional<ResourceRow> resource(UUID id) {
        return jdbc.query(RESOURCE_COLUMNS + " where id = ?", ReviewStore::resource, id).stream().findFirst();
    }

    public List<ResourceRow> resources(UUID projectId, Kind kind) {
        return jdbc.query(RESOURCE_COLUMNS + " where project_id = ? and kind = ? order by title, level", ReviewStore::resource,
                projectId, kind.name());
    }

    public void createResource(UUID id, UUID projectId, Kind kind, String title, String topicId, String level) {
        jdbc.update("insert into resources (id, project_id, kind, title, topic_id, level) values (?, ?, ?, ?, ?, ?)",
                id, projectId, kind.name(), title, topicId, level);
    }

    public void retitle(UUID id, String title) {
        jdbc.update("update resources set title = ? where id = ? and title <> ?", title, id, title);
    }

    /** The newest version of every resource of a kind in a project, with its content. */
    public List<VersionRow> newestOfKind(UUID projectId, Kind kind) {
        return jdbc.query("select distinct on (v.resource_id) " + VERSION_COLUMNS + ", v.content::text from resource_versions v"
                + " join resources r on r.id = v.resource_id left join users u on u.id = v.created_by"
                + " where r.project_id = ? and r.kind = ? order by v.resource_id, v.version_no desc", ReviewStore::version, projectId, kind.name());
    }

    /** Ids of the approved versions of every resource of a kind in a project. */
    public List<UUID> approvedOfKind(UUID projectId, Kind kind) {
        return jdbc.queryForList("select v.id from resource_versions v join resources r on r.id = v.resource_id"
                + " where r.project_id = ? and r.kind = ? and v.status = 'APPROVED'", UUID.class, projectId, kind.name());
    }

    public void link(UUID resourceId, UUID dependsOnId) {
        jdbc.update("insert into resource_links (resource_id, depends_on_id) values (?, ?) on conflict do nothing", resourceId, dependsOnId);
    }

    public void touch(UUID resourceId) {
        jdbc.update("update resources set updated_at = now() where id = ?", resourceId);
    }

    public List<UUID> dependents(UUID resourceId) {
        return jdbc.queryForList(DEPENDENTS_SQL, UUID.class, resourceId);
    }

    // ---- versions ----------------------------------------------------------------------------------

    public List<VersionRow> versions(UUID resourceId) {
        return jdbc.query("select " + VERSION_COLUMNS + ", null from resource_versions v left join users u on u.id = v.created_by"
                + " where v.resource_id = ? order by v.version_no desc", ReviewStore::version, resourceId);
    }

    public Optional<VersionRow> version(UUID id) {
        return jdbc.query("select " + VERSION_COLUMNS + ", v.content::text from resource_versions v left join users u on u.id = v.created_by"
                + " where v.id = ?", ReviewStore::version, id).stream().findFirst();
    }

    public Optional<VersionRow> approved(UUID resourceId) {
        return jdbc.query("select " + VERSION_COLUMNS + ", v.content::text from resource_versions v left join users u on u.id = v.created_by"
                + " where v.resource_id = ? and v.status = 'APPROVED'", ReviewStore::version, resourceId).stream().findFirst();
    }

    public int nextVersionNo(UUID resourceId) {
        Integer n = jdbc.queryForObject("select coalesce(max(version_no), 0) + 1 from resource_versions where resource_id = ?",
                Integer.class, resourceId);
        return n == null ? 1 : n;
    }

    public void insertVersion(UUID id, UUID resourceId, int no, Status status, Origin origin, String content, String hash,
                              UUID basedOn, UUID dependsOn) {
        jdbc.update("""
                insert into resource_versions (id, resource_id, version_no, status, origin, content, content_hash,
                                               based_on_version_id, depends_on_version_id)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, id, resourceId, no, status.name(), origin.name(), content, hash, basedOn, dependsOn);
    }

    /** Changes a draft's content; 0 if it isn't a draft any more. */
    public int saveDraft(UUID id, String content, String hash, UUID dependsOn) {
        return jdbc.update("""
                update resource_versions set content = ?::jsonb, content_hash = ?, depends_on_version_id = ?
                 where id = ? and status = 'DRAFT'
                """, content, hash, dependsOn, id);
    }

    /** Moves a version from one status to another; 0 if it wasn't in {@code from} (someone else got there first). */
    public int move(UUID id, Status from, Status to) {
        return jdbc.update("update resource_versions set status = ? where id = ? and status = ?", to.name(), id, from.name());
    }

    /** Marks every live version of a resource Outdated; returns each version's id and its status before. */
    public List<String[]> outdate(UUID resourceId) {
        return jdbc.query(OUTDATE_SQL,
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, resourceId);
    }

    // ---- the decision record -----------------------------------------------------------------------

    public void event(UUID projectId, UUID resourceId, UUID versionId, String action, Status previous, Status now,
                      String comment, String detailsJson) {
        jdbc.update("""
                insert into review_events (id, project_id, resource_id, version_id, action, previous_status, new_status, comment, details)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), projectId, resourceId, versionId, action,
                previous == null ? null : previous.name(), now.name(), comment, detailsJson == null ? "{}" : detailsJson);
    }

    public List<EventRow> events(UUID resourceId) {
        return jdbc.query("""
                select e.id, e.version_id, v.version_no, e.action, e.previous_status, e.new_status, e.comment, e.details::text,
                       e.actor_id, u.display_name, e.created_at
                  from review_events e join resource_versions v on v.id = e.version_id
                  left join users u on u.id = e.actor_id
                 where e.resource_id = ? order by e.created_at desc, v.version_no desc
                """, (rs, i) -> new EventRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getObject(9, UUID.class), rs.getString(10),
                instant(rs.getTimestamp(11))), resourceId);
    }

    public boolean reviewerMustDiffer(UUID projectId) {
        return jdbc.queryForList("select reviewer_not_author from projects where id = ?", Boolean.class, projectId)
                .stream().findFirst().orElse(Boolean.TRUE);
    }

    // ---- mapping -----------------------------------------------------------------------------------

    private static ResourceRow resource(ResultSet rs, int i) throws SQLException {
        return new ResourceRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), Kind.valueOf(rs.getString(3)),
                rs.getString(4), rs.getString(5), rs.getString(6), instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8)));
    }

    private static VersionRow version(ResultSet rs, int i) throws SQLException {
        return new VersionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), Status.valueOf(rs.getString(4)),
                Origin.valueOf(rs.getString(5)), rs.getString(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class),
                rs.getObject(9, UUID.class), rs.getString(10), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)),
                rs.getString(13));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
