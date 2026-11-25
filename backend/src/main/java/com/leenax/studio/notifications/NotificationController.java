package com.leenax.studio.notifications;

import com.leenax.studio.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The signed-in person's notifications (E10). The database writes them (V18) when a review is
 * asked for or decided, or a job, render or post finishes; row-level security shows each person
 * only their own.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    public record Notification(UUID id, UUID projectId, String projectName, String kind, String title, String body, String link,
                               Instant createdAt, Instant readAt) {
    }

    public record Page(List<Notification> items, long unread) {
    }

    static final String LIST = """
            select n.id, n.project_id, p.name, n.kind, n.title, n.body, n.link, n.created_at, n.read_at
              from notifications n left join projects p on p.id = n.project_id
             where (? = false or n.read_at is null)
             order by n.created_at desc
             limit ?
            """;

    static final String UNREAD = "select count(*) from notifications where read_at is null";

    static final String MARK = "update notifications set read_at = now() where id = ? and read_at is null";

    static final String MARK_ALL = "update notifications set read_at = now() where read_at is null";

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Page list(@RequestParam(defaultValue = "false") boolean unreadOnly, @RequestParam(defaultValue = "30") int limit) {
        List<Notification> items = jdbc.query(LIST, (rs, i) -> new Notification(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8)),
                instant(rs.getTimestamp(9))), unreadOnly, Math.max(1, Math.min(100, limit)));
        return new Page(items, unread());
    }

    /** Just the count, for the bell. */
    @GetMapping("/unread")
    public long unread() {
        Long n = jdbc.queryForObject(UNREAD, Long.class);
        return n == null ? 0 : n;
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        jdbc.update(MARK, id);
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() {
        jdbc.update(MARK_ALL);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        if (jdbc.update("delete from notifications where id = ?", id) == 0) throw ApiException.notFound("Notification");
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
