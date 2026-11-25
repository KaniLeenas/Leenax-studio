package com.leenax.studio.youtube;

import com.leenax.studio.youtube.YouTubeStore.ReachRow;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a channel reach report (Reporting API, channel_reach_basic_a1): a CSV whose first line
 * names the columns. Columns are found by name, so added columns don't break it. A report has a
 * row for each combination of its dimensions (subscribed or not, country and so on); these are
 * summed to one row per day and video, with the click-through rate weighted by impressions.
 *
 * <p>video_thumbnail_impressions_ctr is documented as "the percentage of impressions that resulted
 * in a click", so it is read as a percentage (4.5 means 4.5%) and stored as a fraction.
 */
public final class ReachCsv {

    private ReachCsv() {
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    public static List<ReachRow> parse(String csv) {
        List<List<String>> lines = rows(csv);
        if (lines.isEmpty()) return List.of();
        List<String> head = lines.get(0).stream().map(String::strip).toList();
        int date = head.indexOf("date"), video = head.indexOf("video_id"), imp = head.indexOf("video_thumbnail_impressions"),
                ctr = head.indexOf("video_thumbnail_impressions_ctr");
        if (date < 0 || video < 0 || imp < 0) {
            throw new IllegalArgumentException("The reach report doesn't have the expected columns (date, video_id, video_thumbnail_impressions)");
        }
        record Sum(long impressions, double clicks, boolean hasCtr) {
        }
        Map<String, Sum> sums = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> l = lines.get(i);
            if (l.size() <= Math.max(date, Math.max(video, imp)) || l.get(video).isBlank()) continue;
            long n = (long) Double.parseDouble(l.get(imp).isBlank() ? "0" : l.get(imp));
            if (n < 0) throw new IllegalArgumentException("Negative impressions in the reach report");
            Double rate = null;
            if (ctr >= 0 && ctr < l.size() && !l.get(ctr).isBlank()) {
                double v = Double.parseDouble(l.get(ctr));
                if (v < 0 || v > 100) throw new IllegalArgumentException("A click-through rate outside 0–100% in the reach report");
                rate = v / 100.0;
            }
            String key = l.get(date).strip() + "|" + l.get(video).strip();
            Sum s = sums.getOrDefault(key, new Sum(0, 0, false));
            sums.put(key, new Sum(s.impressions() + n, s.clicks() + (rate == null ? 0 : rate * n), s.hasCtr() || rate != null));
        }
        List<ReachRow> out = new ArrayList<>();
        sums.forEach((k, s) -> {
            String[] p = k.split("\\|", 2);
            Double rate = !s.hasCtr() || s.impressions() == 0 ? null : Math.min(1, s.clicks() / s.impressions());
            out.add(new ReachRow(LocalDate.parse(p[0], DAY), p[1], s.impressions(), rate));
        });
        return out;
    }

    /** RFC 4180 rows (quoted fields, doubled quotes, CRLF or LF). */
    static List<List<String>> rows(String csv) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    f.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(f.toString());
                f.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(f.toString());
                f.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) out.add(row);
                row = new ArrayList<>();
            } else {
                f.append(c);
            }
        }
        if (f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            out.add(row);
        }
        return out;
    }
}
