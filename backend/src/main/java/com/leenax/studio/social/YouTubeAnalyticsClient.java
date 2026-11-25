package com.leenax.studio.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.social.SocialHttp.Response;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YouTube Analytics API v2 (reports.query) and YouTube Reporting API v1 (bulk reach reports)
 * for the signed-in channel (E9). Only the channel's own data; nothing about other channels.
 */
@Component
public class YouTubeAnalyticsClient {

    private static final String NAME = "YouTube Analytics";
    /** Thumbnail impressions and click-through rate, one file a day. */
    public static final String REACH_REPORT = "channel_reach_basic_a1";

    private final SocialHttp http;
    private final SocialProperties props;

    public YouTubeAnalyticsClient(SocialHttp http, SocialProperties props) {
        this.http = http;
        this.props = props;
    }

    // ---- Analytics API ---------------------------------------------------------------------------------

    public record Query(LocalDate start, LocalDate end, String metrics, String dimensions, String filters, String sort, Integer maxResults) {
    }

    /** A report as YouTube returns it: column names in order and the rows. */
    public record Table(List<String> columns, List<List<Object>> rows) {

        public int column(String name) {
            return columns.indexOf(name);
        }
    }

    public Table report(String accessToken, Query q) {
        StringBuilder u = new StringBuilder(props.endpoints().youtubeAnalytics()).append("/reports?ids=channel%3D%3DMINE")
                .append("&startDate=").append(q.start()).append("&endDate=").append(q.end())
                .append("&metrics=").append(enc(q.metrics()));
        if (q.dimensions() != null) u.append("&dimensions=").append(enc(q.dimensions()));
        if (q.filters() != null) u.append("&filters=").append(enc(q.filters()));
        if (q.sort() != null) u.append("&sort=").append(enc(q.sort()));
        if (q.maxResults() != null) u.append("&maxResults=").append(q.maxResults());
        Response r = get(u.toString(), accessToken);
        return table(r.json());
    }

    static Table table(JsonNode json) {
        List<String> cols = new ArrayList<>();
        for (JsonNode h : json.path("columnHeaders")) cols.add(h.path("name").asText());
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode row : json.path("rows")) {
            List<Object> out = new ArrayList<>();
            for (JsonNode v : row) out.add(v.isNumber() ? (Object) v.numberValue() : v.isNull() ? null : v.asText());
            rows.add(out);
        }
        return new Table(cols, rows);
    }

    // ---- Reporting API ---------------------------------------------------------------------------------

    public record ReportingJob(String id, String reportTypeId, String name, Instant createTime) {
    }

    public List<ReportingJob> jobs(String accessToken) {
        List<ReportingJob> out = new ArrayList<>();
        String page = null;
        for (int i = 0; i < 10; i++) {
            Response r = get(props.endpoints().youtubeReporting() + "/jobs" + (page == null ? "" : "?pageToken=" + enc(page)), accessToken);
            for (JsonNode j : r.json().path("jobs")) {
                out.add(new ReportingJob(j.path("id").asText(), j.path("reportTypeId").asText(), j.path("name").asText(null),
                        instant(j.path("createTime").asText(null))));
            }
            page = r.json().path("nextPageToken").asText(null);
            if (page == null) break;
        }
        return out;
    }

    public ReportingJob createJob(String accessToken, String reportTypeId, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportTypeId", reportTypeId);
        body.put("name", name);
        Response r = http.send(SocialHttp.request(props.endpoints().youtubeReporting() + "/jobs", Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .POST(http.json(body)).build(), NAME);
        if (!r.ok()) throw YouTubeClient.apiError(r);
        JsonNode j = r.json();
        return new ReportingJob(j.path("id").asText(), j.path("reportTypeId").asText(), j.path("name").asText(null),
                instant(j.path("createTime").asText(null)));
    }

    public record Report(String id, Instant startTime, Instant endTime, Instant createTime, String downloadUrl) {
    }

    /** Reports of a job created after a time (null: all YouTube still keeps). */
    public List<Report> reports(String accessToken, String jobId, Instant createdAfter) {
        List<Report> out = new ArrayList<>();
        String page = null;
        for (int i = 0; i < 20; i++) {
            StringBuilder u = new StringBuilder(props.endpoints().youtubeReporting()).append("/jobs/").append(enc(jobId)).append("/reports");
            String sep = "?";
            if (createdAfter != null) {
                u.append(sep).append("createdAfter=").append(enc(createdAfter.toString()));
                sep = "&";
            }
            if (page != null) u.append(sep).append("pageToken=").append(enc(page));
            Response r = get(u.toString(), accessToken);
            for (JsonNode j : r.json().path("reports")) {
                out.add(new Report(j.path("id").asText(), instant(j.path("startTime").asText(null)), instant(j.path("endTime").asText(null)),
                        instant(j.path("createTime").asText(null)), j.path("downloadUrl").asText(null)));
            }
            page = r.json().path("nextPageToken").asText(null);
            if (page == null) break;
        }
        return out;
    }

    /** Downloads a report file (CSV). Only Google's report download addresses are fetched. */
    public String download(String accessToken, String url) {
        if (url == null || !url.startsWith(props.endpoints().youtubeReporting().replaceAll("/v1$", ""))) {
            throw new SocialException(SocialException.Kind.REJECTED, "Unexpected report address");
        }
        Response r = http.send(SocialHttp.request(url, Duration.ofMinutes(2)).header("Authorization", "Bearer " + accessToken).GET().build(), NAME);
        if (!r.ok()) throw YouTubeClient.apiError(r);
        return r.body();
    }

    private Response get(String url, String accessToken) {
        Response r = http.send(SocialHttp.request(url, Duration.ofSeconds(30)).header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json").GET().build(), NAME);
        if (!r.ok()) throw YouTubeClient.apiError(r);
        return r;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static Instant instant(String s) {
        try {
            return s == null || s.isBlank() ? null : Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
