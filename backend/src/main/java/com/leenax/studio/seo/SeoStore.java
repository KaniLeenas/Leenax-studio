package com.leenax.studio.seo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Kind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for YouTube SEO (V14): the quota ledger, cached searches, Trends imports and research
 * results. Also a review check that every number in an SEO report and its research is labelled.
 */
@Component
public class SeoStore implements Review.Gate {

    static final ObjectMapper JSON = new ObjectMapper();
    /** YouTube counts its daily quota by Pacific time. */
    public static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    private final JdbcTemplate jdbc;

    public SeoStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- quota -------------------------------------------------------------------------------------

    public static LocalDate quotaDay(Instant now) {
        return now.atZone(QUOTA_ZONE).toLocalDate();
    }

    /** Searches and units used today across every project ({searches, units}). */
    public long[] used(LocalDate day) {
        return jdbc.query("select searches, units from leenax_seo_quota_used(?)", (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, day)
                .stream().findFirst().orElse(new long[]{0, 0});
    }

    public void recordCall(UUID projectId, LocalDate day, String method, int units, String query, boolean ok) {
        jdbc.update("insert into seo_api_calls (id, project_id, quota_day, method, units, query, ok) values (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), projectId, day, method, units, query, ok);
    }

    // ---- cache -------------------------------------------------------------------------------------

    public record Cached(String query, String language, String region, JsonNode results, Instant retrievedAt) {
    }

    public static String cacheKey(String query, String language, String region) {
        return (language == null ? "" : language) + "|" + (region == null ? "" : region) + "|"
                + query.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    public Optional<Cached> cached(UUID projectId, String key, Instant notBefore) {
        return jdbc.query("select query, language, region, results::text, retrieved_at from seo_search_cache"
                        + " where project_id = ? and query_key = ? and retrieved_at >= ? order by retrieved_at desc limit 1",
                (rs, i) -> new Cached(rs.getString(1), rs.getString(2), rs.getString(3), read(rs.getString(4)), instant(rs.getTimestamp(5))),
                projectId, key, Timestamp.from(notBefore)).stream().findFirst();
    }

    public void cache(UUID projectId, String key, String query, String language, String region, Object results, Instant at) {
        jdbc.update("insert into seo_search_cache (id, project_id, query_key, query, language, region, results, retrieved_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)", UUID.randomUUID(), projectId, key, query, language, region, write(results),
                Timestamp.from(at));
    }

    // ---- Trends imports ----------------------------------------------------------------------------

    public record TrendsRow(UUID id, String filename, String geo, String period, LocalDate firstDate, LocalDate lastDate, JsonNode terms,
                            JsonNode series, String status, String createdBy, Instant createdAt) {
    }

    static final String TRENDS_SQL = """
            select t.id, t.filename, t.geo, t.period, t.first_date, t.last_date, t.terms::text, t.series::text, t.status, u.display_name,
                   t.created_at
              from seo_trends t left join users u on u.id = t.created_by
            """;

    public void insertTrends(UUID id, UUID projectId, String filename, SeoLogic.Trends t) {
        jdbc.update("insert into seo_trends (id, project_id, filename, geo, period, first_date, last_date, terms, series)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)", id, projectId, filename, t.geo(), t.period(), t.first(), t.last(),
                write(t.terms()), write(t.series()));
    }

    public List<TrendsRow> trends(UUID projectId, boolean activeOnly) {
        return jdbc.query(TRENDS_SQL + " where t.project_id = ?" + (activeOnly ? " and t.status = 'ACTIVE'" : "") + " order by t.created_at desc",
                SeoStore::trendsRow, projectId);
    }

    public Optional<TrendsRow> trendsFile(UUID id) {
        return jdbc.query(TRENDS_SQL + " where t.id = ?", SeoStore::trendsRow, id).stream().findFirst();
    }

    public UUID projectOfTrends(UUID id) {
        return jdbc.queryForList("select project_id from seo_trends where id = ?", UUID.class, id).stream().findFirst().orElse(null);
    }

    public int removeTrends(UUID id) {
        return jdbc.update("update seo_trends set status = 'REMOVED' where id = ? and status = 'ACTIVE'", id);
    }

    // ---- research ----------------------------------------------------------------------------------

    public record ResearchRow(UUID id, UUID projectId, String topicId, String status, JsonNode data, String createdBy, Instant createdAt) {
    }

    static final String RESEARCH_SQL = """
            select r.id, r.project_id, r.topic_id, r.status, r.data::text, u.display_name, r.created_at
              from seo_research r left join users u on u.id = r.created_by
            """;

    public void insertResearch(UUID id, UUID projectId, String topicId, UUID jobId, String status, Object data) {
        jdbc.update("insert into seo_research (id, project_id, topic_id, job_id, status, data) values (?, ?, ?, ?, ?, ?::jsonb)",
                id, projectId, topicId, jobId, status, write(data));
    }

    public Optional<ResearchRow> research(UUID id) {
        return jdbc.query(RESEARCH_SQL + " where r.id = ?", SeoStore::researchRow, id).stream().findFirst();
    }

    /** The newest research for each topic of a project (data left out). */
    public List<ResearchRow> latestPerTopic(UUID projectId) {
        return jdbc.query("select distinct on (r.topic_id) r.id, r.project_id, r.topic_id, r.status, null, u.display_name, r.created_at"
                + " from seo_research r left join users u on u.id = r.created_by where r.project_id = ?"
                + " order by r.topic_id, r.created_at desc", SeoStore::researchRow, projectId);
    }

    // ---- the label rule, as a review check ---------------------------------------------------------

    @Override
    public List<Check> checks(UUID projectId, UUID resourceId, Kind kind, String contentHash) {
        if (kind != Kind.SEO) return List.of();
        String content = jdbc.queryForList("select content::text from resource_versions where resource_id = ? and content_hash = ?"
                + " order by version_no desc limit 1", String.class, resourceId, contentHash).stream().findFirst().orElse(null);
        if (content == null) return List.of();
        JsonNode c = read(content);
        List<Check> out = new ArrayList<>();
        UUID researchId = uuid(c.path("researchId").asText(null));
        Optional<ResearchRow> r = researchId == null ? Optional.empty() : research(researchId);
        if (r.isEmpty() || !r.get().projectId().equals(projectId) || !r.get().topicId().equals(c.path("topicId").asText())) {
            out.add(Check.fail("research", "The research this report is built on can't be found for this topic"));
            return out;
        }
        List<String> bad = new ArrayList<>(SeoLogic.unlabelledNumbers(r.get().data()));
        bad.replaceAll(p -> "research " + p);
        out.add(bad.isEmpty() ? Check.pass("research-labels", "Every number in the research has a source or an “estimated” label")
                : Check.fail("research-labels", "Numbers without a source or an “estimated” label in the research: " + String.join(", ", bad.stream().limit(5).toList())));
        if ("PARTIAL".equals(r.get().status())) out.add(Check.warn("research-partial", "The research is partial; see its limitations"));
        Integer qa = jdbc.queryForObject("select count(*) from resources r join resource_versions v on v.resource_id = r.id"
                + " where r.project_id = ? and r.kind = 'QA' and r.topic_id = ? and v.status = 'APPROVED'", Integer.class, projectId,
                c.path("topicId").asText());
        out.add(qa != null && qa > 0 ? Check.pass("academic", "The topic's academic QA report is approved")
                : Check.warn("academic", "The topic's academic QA report isn't approved yet; slides need both approvals"));
        return out;
    }

    // ---- mapping -----------------------------------------------------------------------------------

    private static TrendsRow trendsRow(ResultSet rs, int i) throws SQLException {
        java.sql.Date f = rs.getDate(5), l = rs.getDate(6);
        return new TrendsRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), f == null ? null : f.toLocalDate(),
                l == null ? null : l.toLocalDate(), read(rs.getString(7)), read(rs.getString(8)), rs.getString(9), rs.getString(10),
                instant(rs.getTimestamp(11)));
    }

    private static ResearchRow researchRow(ResultSet rs, int i) throws SQLException {
        return new ResearchRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), read(rs.getString(5)),
                rs.getString(6), instant(rs.getTimestamp(7)));
    }

    static JsonNode read(String s) {
        if (s == null) return null;
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("Stored SEO data isn't JSON", e);
        }
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
