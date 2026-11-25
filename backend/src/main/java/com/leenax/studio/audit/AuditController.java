package com.leenax.studio.audit;

import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only view of the audit trail: sign-ins, security changes, connections, publishing, deletions. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    /** {@code userName}: who did it (shown to administrators; members only see their own). */
    public record Entry(UUID id, String action, String entityType, UUID entityId, String ipAddress,
                        Map<String, Object> details, Instant createdAt, String userName) {
    }

    /** Which actions each filter shows (SQL LIKE patterns). */
    static final Map<String, List<String>> GROUPS = Map.of(
            "security", List.of("LOGIN%", "MFA%", "PASSWORD%", "SETUP%", "OWNER%"),
            "accounts", List.of("SOCIAL%", "ACCOUNT%", "AI_ACCOUNT%", "API_KEY%"),
            "publishing", List.of("POST%"),
            "files", List.of("MEDIA%", "PROJECT%", "TIMELINE%", "VOICE%", "DOCUMENT%", "REVIEW%", "SYLLABUS%"),
            "people", List.of("USER%", "SEAT%", "ACTIVATION%", "RESET%", "ACCOUNT_ACTIVATED", "PROJECT_MEMBER%"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final com.leenax.studio.auth.CurrentUser current;

    public AuditController(JdbcTemplate jdbc, ObjectMapper mapper, com.leenax.studio.auth.CurrentUser current) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.current = current;
    }

    @GetMapping
    public PageResponse<Entry> list(@RequestParam(defaultValue = "all") String group,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 200) throw ApiException.badRequest("Invalid page");
        List<String> patterns = group.equals("all") ? List.of("%") : GROUPS.get(group);
        if (patterns == null) throw ApiException.badRequest("Unknown group");
        var me = current.require();
        // Administrators see everyone's activity; others see their own.
        String where = "(" + String.join(" OR ", patterns.stream().map(p -> "a.action LIKE ?").toList()) + ")";
        List<Object> argList = new java.util.ArrayList<>(patterns);
        if (!me.isAdmin()) {
            where += " AND a.user_id = ?";
            argList.add(me.getId());
        }
        Object[] args = argList.toArray();
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_logs a WHERE " + where, Long.class, args);
        Object[] pageArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pageArgs, 0, args.length);
        pageArgs[args.length] = size;
        pageArgs[args.length + 1] = (long) page * size;
        List<Entry> items = jdbc.query("SELECT a.id, a.action, a.entity_type, a.entity_id, a.ip_address, a.details::text, a.created_at, u.display_name"
                + " FROM audit_logs a LEFT JOIN users u ON u.id = a.user_id WHERE "
                + where + " ORDER BY a.created_at DESC LIMIT ? OFFSET ?", (rs, i) -> new Entry(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                parse(rs.getString(6)), rs.getTimestamp(7).toInstant(), rs.getString(8)), pageArgs);
        long t = total == null ? 0 : total;
        return new PageResponse<>(items, page, size, t, (int) ((t + size - 1) / size));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return json == null ? Map.of() : mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
