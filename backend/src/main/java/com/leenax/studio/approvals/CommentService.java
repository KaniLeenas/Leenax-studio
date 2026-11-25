package com.leenax.studio.approvals;

import com.leenax.studio.approvals.ReviewStore.ResourceRow;
import com.leenax.studio.approvals.ReviewStore.VersionRow;
import com.leenax.studio.auth.CurrentUser;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.projects.ProjectService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comments on a version (E4), optionally on a quoted passage. They sit beside the content:
 * commenting never changes a version or its approval. Editors and reviewers comment and
 * resolve; a comment's text never changes (V12).
 */
@Service
public class CommentService {

    public record Comment(UUID id, UUID versionId, int versionNo, String quote, String body, UUID authorId, String author,
                          Instant createdAt, Instant resolvedAt, String resolvedBy) {
    }

    public record NewComment(@Size(max = 500) String quote, @NotBlank @Size(max = 2000) String body) {
    }

    public record Resolve(boolean resolved) {
    }

    private final JdbcTemplate jdbc;
    private final ReviewStore store;
    private final ProjectService projects;
    private final CurrentUser current;

    public CommentService(JdbcTemplate jdbc, ReviewStore store, ProjectService projects, CurrentUser current) {
        this.jdbc = jdbc;
        this.store = store;
        this.projects = projects;
        this.current = current;
    }

    public List<Comment> list(UUID resourceId) {
        ResourceRow r = store.resource(resourceId).orElseThrow(() -> ApiException.notFound("Resource"));
        projects.requireAccess(r.projectId(), false, null);
        return jdbc.query("""
                select c.id, c.version_id, v.version_no, c.quote, c.body, c.author_id, a.display_name, c.created_at, c.resolved_at, b.display_name
                  from review_comments c join resource_versions v on v.id = c.version_id
                  left join users a on a.id = c.author_id left join users b on b.id = c.resolved_by
                 where c.resource_id = ? order by c.created_at
                """, (rs, i) -> new Comment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9)),
                rs.getString(10)), resourceId);
    }

    public List<Comment> add(UUID versionId, NewComment req) {
        VersionRow v = store.version(versionId).orElseThrow(() -> ApiException.notFound("Version"));
        ResourceRow r = store.resource(v.resourceId()).orElseThrow();
        projects.requireAccess(r.projectId(), false, null);
        if (!projects.canEdit(r.projectId()) && !projects.canReview(r.projectId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the project's owners, editors and reviewers can comment");
        }
        String quote = req.quote() == null || req.quote().isBlank() ? null : req.quote().strip();
        jdbc.update("insert into review_comments (id, project_id, resource_id, version_id, quote, body) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), r.projectId(), r.id(), v.id(), quote, req.body().strip());
        return list(r.id());
    }

    public List<Comment> resolve(UUID commentId, boolean resolved) {
        UUID resource = resourceOf(commentId);
        int n = resolved
                ? jdbc.update("update review_comments set resolved_at = now(), resolved_by = ? where id = ? and resolved_at is null", current.require().getId(), commentId)
                : jdbc.update("update review_comments set resolved_at = null, resolved_by = null where id = ? and resolved_at is not null", commentId);
        if (n == 0 && !projects.canEdit(store.resource(resource).orElseThrow().projectId())
                && !projects.canReview(store.resource(resource).orElseThrow().projectId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the project's owners, editors and reviewers can resolve comments");
        }
        return list(resource);
    }

    public List<Comment> delete(UUID commentId) {
        UUID resource = resourceOf(commentId);
        if (jdbc.update("delete from review_comments where id = ?", commentId) == 0) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You can delete only your own comments, while they're open");
        }
        return list(resource);
    }

    private UUID resourceOf(UUID commentId) {
        return jdbc.queryForList("select resource_id from review_comments where id = ?", UUID.class, commentId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Comment"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    @RestController
    @RequestMapping("/api/v1/reviews")
    public static class Controller {
        private final CommentService service;

        public Controller(CommentService service) {
            this.service = service;
        }

        @GetMapping("/resources/{resourceId}/comments")
        public List<Comment> list(@PathVariable UUID resourceId) {
            return service.list(resourceId);
        }

        @PostMapping("/versions/{versionId}/comments")
        @ResponseStatus(HttpStatus.CREATED)
        public List<Comment> add(@PathVariable UUID versionId, @Valid @RequestBody NewComment req) {
            return service.add(versionId, req);
        }

        @PostMapping("/comments/{commentId}/resolve")
        public List<Comment> resolve(@PathVariable UUID commentId, @RequestBody Resolve req) {
            return service.resolve(commentId, req.resolved());
        }

        @DeleteMapping("/comments/{commentId}")
        public List<Comment> delete(@PathVariable UUID commentId) {
            return service.delete(commentId);
        }
    }
}
