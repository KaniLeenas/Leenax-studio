package com.leenax.studio.projects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who belongs to which project, and in what role. The table has no row-level security
 * of its own (the policies read it), so every caller checks first that the project is
 * visible to the person asking: {@link ProjectService} does.
 */
@Component
public class ProjectMembers {

    public enum Role { OWNER, EDITOR, REVIEWER, VIEWER;

        public boolean canEdit() {
            return this == OWNER || this == EDITOR;
        }

        public boolean canReview() {
            return this == OWNER || this == REVIEWER;
        }
    }

    public record Member(UUID userId, String displayName, String email, Role role, String status, Instant addedAt) {
    }

    private final JdbcTemplate jdbc;

    public ProjectMembers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Role> role(UUID projectId, UUID userId) {
        if (projectId == null || userId == null) return Optional.empty();
        return jdbc.queryForList("select role from project_members where project_id = ? and user_id = ?", String.class, projectId, userId)
                .stream().findFirst().map(Role::valueOf);
    }

    public List<Member> list(UUID projectId) {
        return jdbc.query("""
                select m.user_id, u.display_name, u.email, m.role, u.status, m.created_at
                  from project_members m join users u on u.id = m.user_id
                 where m.project_id = ?
                 order by case m.role when 'OWNER' then 0 when 'EDITOR' then 1 when 'REVIEWER' then 2 else 3 end, u.display_name
                """, (rs, i) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                Role.valueOf(rs.getString(4)), rs.getString(5), toInstant(rs.getTimestamp(6))), projectId);
    }

    public void put(UUID projectId, UUID userId, Role role, UUID addedBy) {
        jdbc.update("""
                insert into project_members (project_id, user_id, role, added_by) values (?, ?, ?, ?)
                on conflict (project_id, user_id) do update set role = excluded.role
                """, projectId, userId, role.name(), addedBy);
    }

    public boolean remove(UUID projectId, UUID userId) {
        return jdbc.update("delete from project_members where project_id = ? and user_id = ?", projectId, userId) > 0;
    }

    public long owners(UUID projectId) {
        Long n = jdbc.queryForObject("select count(*) from project_members where project_id = ? and role = 'OWNER'", Long.class, projectId);
        return n == null ? 0 : n;
    }

    /** This person's role in each of the given projects (absent = not a member). */
    public Map<UUID, Role> rolesOf(UUID userId, Collection<UUID> projectIds) {
        Map<UUID, Role> out = new HashMap<>();
        if (userId == null || projectIds.isEmpty()) return out;
        List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        args.addAll(projectIds);
        jdbc.query("select project_id, role from project_members where user_id = ? and project_id in (" + marks(projectIds.size()) + ")",
                (rs, i) -> out.put(rs.getObject(1, UUID.class), Role.valueOf(rs.getString(2))), args.toArray());
        return out;
    }

    public Map<UUID, Long> counts(Collection<UUID> projectIds) {
        Map<UUID, Long> out = new HashMap<>();
        if (projectIds.isEmpty()) return out;
        jdbc.query("select project_id, count(*) from project_members where project_id in (" + marks(projectIds.size()) + ") group by project_id",
                (rs, i) -> out.put(rs.getObject(1, UUID.class), rs.getLong(2)), projectIds.toArray());
        return out;
    }

    static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
