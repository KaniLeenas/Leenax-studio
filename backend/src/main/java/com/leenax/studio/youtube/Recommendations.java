package com.leenax.studio.youtube;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recommendations from the channel's own analytics (E9, module 23). Every recommendation is a
 * stated rule applied to numbers YouTube reported, and carries those numbers and where they came
 * from. Nothing here is about other channels: their analytics are private.
 */
public final class Recommendations {

    private Recommendations() {
    }

    /** One video's numbers for the period (null where YouTube reported nothing). */
    public record VideoStat(String videoId, String title, String topicId, String topicTitle, Long views, Double minutesWatched,
                            Double averageViewDuration, Double averageViewPercentage, Long subscribersGained, Long likes, Long comments,
                            Long shares, Long impressions, Double ctr, Double durationSeconds, List<String> otherApprovedTitles) {
    }

    /** A point of the audience retention curve: ratio of the video elapsed, and the watch ratios there. */
    public record RetentionPoint(double ratio, double audienceWatchRatio, Double relativeRetentionPerformance) {
    }

    /** A scene of the published video: when it plays and what it's called. */
    public record Scene(double start, double end, String title) {
    }

    public record Evidence(String metric, String value, String source) {
    }

    public record Recommendation(String kind, String title, String detail, String rule, String videoId, List<Evidence> evidence) {
    }

    public record Input(List<VideoStat> videos, Map<String, List<RetentionPoint>> retention, Map<String, List<Scene>> scenes,
                        Map<String, Long> trafficViews, Map<String, Long> searchTerms, List<String> unpublishedTopics,
                        String analyticsSource, String reachSource) {
    }

    /** Minimums before a rule says anything: too few views make percentages noise. */
    static final long MIN_VIEWS = 100, MIN_IMPRESSIONS = 1000;
    static final double LOW_CTR = 0.02, DROP = 0.10, INTRO_DROP = 0.30;

    public static List<Recommendation> build(Input in) {
        List<Recommendation> out = new ArrayList<>();
        bestSubjects(in, out);
        underperforming(in, out);
        for (VideoStat v : in.videos()) retention(in, v, out);
        titlesAndThumbnails(in, out);
        futureTopics(in, out);
        strategy(in, out);
        return out;
    }

    // ---- best subjects -------------------------------------------------------------------------------

    static void bestSubjects(Input in, List<Recommendation> out) {
        record Agg(String title, long views, double weightedPct, long weight, int videos) {
        }
        Map<String, Agg> by = new LinkedHashMap<>();
        for (VideoStat v : in.videos()) {
            if (v.views() == null || v.topicId() == null) continue;
            Agg a = by.getOrDefault(v.topicId(), new Agg(v.topicTitle() == null ? v.topicId() : v.topicTitle(), 0, 0, 0, 0));
            double pct = v.averageViewPercentage() == null ? 0 : v.averageViewPercentage() * v.views();
            by.put(v.topicId(), new Agg(a.title(), a.views() + v.views(), a.weightedPct() + pct,
                    a.weight() + (v.averageViewPercentage() == null ? 0 : v.views()), a.videos() + 1));
        }
        if (by.size() < 2) return;
        List<Map.Entry<String, Agg>> ranked = new ArrayList<>(by.entrySet());
        ranked.removeIf(e -> e.getValue().views() < MIN_VIEWS);
        if (ranked.isEmpty()) return;
        ranked.sort(Comparator.comparingLong((Map.Entry<String, Agg> e) -> e.getValue().views()).reversed());
        Agg top = ranked.get(0).getValue();
        long total = by.values().stream().mapToLong(Agg::views).sum();
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence("Views", fmt(top.views()) + " of " + fmt(total) + " (" + pct((double) top.views() / Math.max(1, total)) + ")", in.analyticsSource()));
        if (top.weight() > 0) ev.add(new Evidence("Average percentage viewed", pct(top.weightedPct() / top.weight() / 100), in.analyticsSource()));
        out.add(new Recommendation("BEST_SUBJECT", "“" + top.title() + "” is your strongest subject",
                "Its " + top.videos() + " video" + (top.videos() == 1 ? "" : "s") + " drew the most views of this project's subjects in the period. "
                        + "More videos on it, or on the subjects next to it in the syllabus, are the safest next step.",
                "Subjects ranked by total views of their published videos (subjects with at least " + MIN_VIEWS + " views).", null, ev));
    }

    // ---- underperforming videos ----------------------------------------------------------------------

    static void underperforming(Input in, List<Recommendation> out) {
        List<VideoStat> withViews = in.videos().stream().filter(v -> v.views() != null).toList();
        if (withViews.size() < 3) return;
        double median = median(withViews.stream().map(v -> (double) v.views()).toList());
        if (median < MIN_VIEWS) return;
        List<Double> pcts = withViews.stream().map(VideoStat::averageViewPercentage).filter(x -> x != null).toList();
        Double pctMedian = pcts.size() >= 3 ? median(pcts) : null;
        for (VideoStat v : withViews) {
            if (v.views() >= median / 2) continue;
            List<Evidence> ev = new ArrayList<>();
            ev.add(new Evidence("Views", fmt(v.views()) + " (project median " + fmt(Math.round(median)) + ")", in.analyticsSource()));
            String why;
            if (v.impressions() != null && v.ctr() != null && v.impressions() >= MIN_IMPRESSIONS && v.ctr() < LOW_CTR) {
                ev.add(new Evidence("Impressions click-through rate", pct(v.ctr()) + " of " + fmt(v.impressions()) + " impressions", in.reachSource()));
                why = "YouTube showed it, but few people clicked: the title and thumbnail are the first things to change.";
            } else if (v.impressions() != null && v.impressions() < MIN_IMPRESSIONS) {
                ev.add(new Evidence("Impressions", fmt(v.impressions()), in.reachSource()));
                why = "YouTube showed it rarely. Link it from the subject's playlist, the description of related videos and an end screen.";
            } else if (pctMedian != null && v.averageViewPercentage() != null && v.averageViewPercentage() < pctMedian) {
                ev.add(new Evidence("Average percentage viewed", pct(v.averageViewPercentage() / 100) + " (median " + pct(pctMedian / 100) + ")",
                        in.analyticsSource()));
                why = "People leave earlier than in your other videos. See the drop-off points below.";
            } else {
                why = "Compare its title, thumbnail and first 30 seconds with your best video on a similar subject.";
            }
            out.add(new Recommendation("UNDERPERFORMING", "“" + v.title() + "” is below your usual views", why,
                    "A video with less than half the project's median views in the period (needs 3 or more published videos and a median of at least "
                            + MIN_VIEWS + " views).", v.videoId(), ev));
        }
    }

    // ---- retention: drop-offs and strong sections ---------------------------------------------------------

    static void retention(Input in, VideoStat v, List<Recommendation> out) {
        List<RetentionPoint> curve = in.retention().get(v.videoId());
        if (curve == null || curve.size() < 10 || v.views() == null || v.views() < MIN_VIEWS) return;
        List<Scene> scenes = in.scenes().getOrDefault(v.videoId(), List.of());
        Double length = v.durationSeconds();
        // the opening
        RetentionPoint first = curve.get(0);
        RetentionPoint tenth = curve.stream().filter(p -> p.ratio() >= 0.1).findFirst().orElse(null);
        if (tenth != null && first.audienceWatchRatio() - tenth.audienceWatchRatio() >= INTRO_DROP) {
            out.add(new Recommendation("DROP_OFF", "Many viewers leave “" + v.title() + "” in its opening",
                    "Start with the question the video answers, and show what the viewer will be able to do by the end. " + where(0, 0.1, length, scenes),
                    "A fall of " + pct(INTRO_DROP) + " or more of the audience in the first 10% of the video.", v.videoId(),
                    List.of(new Evidence("Audience watch ratio", ratio(first.audienceWatchRatio()) + " → " + ratio(tenth.audienceWatchRatio()) + " by 10%",
                            in.analyticsSource()))));
        }
        // the steepest fall after the opening, over a 5% window
        double worst = 0;
        int at = -1;
        for (int i = 0; i < curve.size(); i++) {
            RetentionPoint p = curve.get(i);
            if (p.ratio() < 0.1 || p.ratio() > 0.95) continue;
            RetentionPoint q = next(curve, i, 0.05);
            if (q == null) break;
            double fall = p.audienceWatchRatio() - q.audienceWatchRatio();
            if (fall > worst) {
                worst = fall;
                at = i;
            }
        }
        if (at >= 0 && worst >= DROP) {
            RetentionPoint p = curve.get(at), q = next(curve, at, 0.05);
            out.add(new Recommendation("DROP_OFF", "A drop-off in “" + v.title() + "”",
                    where(p.ratio(), q.ratio(), length, scenes) + " Check whether this part repeats something, runs long, or needs a picture.",
                    "The largest fall in the audience over 5% of the video after its opening, when it is " + pct(DROP) + " or more.", v.videoId(),
                    List.of(new Evidence("Audience watch ratio", ratio(p.audienceWatchRatio()) + " → " + ratio(q.audienceWatchRatio()), in.analyticsSource()))));
        }
        // strong sections: rewatched parts, or retention above similar videos
        for (int i = 1; i < curve.size(); i++) {
            RetentionPoint prev = curve.get(i - 1), p = curve.get(i);
            boolean rewatch = p.audienceWatchRatio() > prev.audienceWatchRatio() + 0.02 && p.ratio() > 0.05;
            boolean above = p.relativeRetentionPerformance() != null && p.relativeRetentionPerformance() >= 0.75 && p.ratio() > 0.1;
            if (!rewatch && !above) continue;
            RetentionPoint end = p;
            int j = i;
            while (j + 1 < curve.size() && (curve.get(j + 1).audienceWatchRatio() >= end.audienceWatchRatio()
                    || (curve.get(j + 1).relativeRetentionPerformance() != null && curve.get(j + 1).relativeRetentionPerformance() >= 0.75))) {
                end = curve.get(++j);
            }
            List<Evidence> ev = new ArrayList<>();
            if (rewatch) ev.add(new Evidence("Audience watch ratio", ratio(prev.audienceWatchRatio()) + " → " + ratio(p.audienceWatchRatio())
                    + " (viewers went back to watch it again)", in.analyticsSource()));
            if (above) ev.add(new Evidence("Relative retention", ratio(p.relativeRetentionPerformance())
                    + " (0.5 is typical for videos of this length on YouTube)", in.analyticsSource()));
            out.add(new Recommendation("STRONG_SECTION", "A strong part of “" + v.title() + "”",
                    where(p.ratio(), end.ratio(), length, scenes) + " Use what works here (its example, pace or picture) in other videos, "
                            + "and consider a Short from it.",
                    "Rewatched (the audience ratio rises), or relative retention of 0.75 or more.", v.videoId(), ev));
            break;   // the first strong section only, to keep the list short
        }
    }

    static RetentionPoint next(List<RetentionPoint> curve, int i, double span) {
        double target = curve.get(i).ratio() + span;
        for (int j = i + 1; j < curve.size(); j++) if (curve.get(j).ratio() >= target - 1e-9) return curve.get(j);
        return null;
    }

    /** Where a part of the video is, in time and by scene. */
    static String where(double fromRatio, double toRatio, Double length, List<Scene> scenes) {
        if (length == null || length <= 0) return "At " + pct(fromRatio) + "–" + pct(toRatio) + " of the video.";
        double a = fromRatio * length, b = toRatio * length;
        StringBuilder s = new StringBuilder("From " + clock(a) + " to " + clock(b));
        List<String> names = new ArrayList<>();
        for (Scene sc : scenes) if (sc.end() > a && sc.start() < b && sc.title() != null && !names.contains(sc.title())) names.add(sc.title());
        if (!names.isEmpty()) s.append(names.size() == 1 ? ", in the scene “" : ", in the scenes “").append(String.join("”, “", names)).append("”");
        return s.append('.').toString();
    }

    // ---- titles and thumbnails -----------------------------------------------------------------------------

    static void titlesAndThumbnails(Input in, List<Recommendation> out) {
        List<Double> ctrs = in.videos().stream().filter(v -> v.ctr() != null && v.impressions() != null && v.impressions() >= MIN_IMPRESSIONS)
                .map(VideoStat::ctr).toList();
        Double median = ctrs.size() >= 3 ? median(ctrs) : null;
        for (VideoStat v : in.videos()) {
            if (v.ctr() == null || v.impressions() == null || v.impressions() < MIN_IMPRESSIONS) continue;
            boolean low = v.ctr() < LOW_CTR || (median != null && v.ctr() < median * 0.6);
            if (!low) continue;
            String others = v.otherApprovedTitles() == null || v.otherApprovedTitles().isEmpty() ? ""
                    : " Other titles approved in its SEO report: “" + String.join("”, “", v.otherApprovedTitles().subList(0, Math.min(3, v.otherApprovedTitles().size()))) + "”.";
            List<Evidence> ev = new ArrayList<>();
            ev.add(new Evidence("Impressions click-through rate", pct(v.ctr()) + " of " + fmt(v.impressions()) + " impressions", in.reachSource()));
            if (median != null) ev.add(new Evidence("Project median click-through rate", pct(median), in.reachSource()));
            boolean holds = v.averageViewPercentage() != null && v.averageViewPercentage() >= 50;
            out.add(new Recommendation("TITLE_THUMBNAIL", "Improve the title or thumbnail of “" + v.title() + "”",
                    (holds ? "People who click watch most of it, so the video is fine; the packaging is what holds it back."
                            : "Try a title that names the exact skill or exam question, and a thumbnail with one large, readable phrase.") + others
                            + " Change one thing at a time and compare the next 7 days.",
                    "Click-through rate under " + pct(LOW_CTR) + ", or under 60% of the project median, with at least " + fmt(MIN_IMPRESSIONS) + " impressions.",
                    v.videoId(), ev));
        }
    }

    // ---- future topics --------------------------------------------------------------------------------

    static void futureTopics(Input in, List<Recommendation> out) {
        List<String> covered = new ArrayList<>();
        for (VideoStat v : in.videos()) {
            if (v.title() != null) covered.add(v.title().toLowerCase(Locale.ROOT));
            if (v.topicTitle() != null) covered.add(v.topicTitle().toLowerCase(Locale.ROOT));
        }
        List<Evidence> ev = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        in.searchTerms().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).forEach(e -> {
            String t = e.getKey().toLowerCase(Locale.ROOT).strip();
            if (terms.size() >= 5 || t.isEmpty() || e.getValue() < 5) return;
            boolean seen = covered.stream().anyMatch(c -> c.contains(t));
            if (!seen) {
                terms.add(e.getKey());
                ev.add(new Evidence("YouTube search term", "“" + e.getKey() + "”: " + fmt(e.getValue()) + " views", in.analyticsSource()));
            }
        });
        if (!terms.isEmpty()) {
            out.add(new Recommendation("FUTURE_TOPIC", "Searches that brought viewers but have no video of their own",
                    "Viewers found your videos by searching for these. A video that answers one of them directly may do well: "
                            + "check that it fits the syllabus before planning it.",
                    "YouTube search terms with 5 or more views whose words don't appear in a published title or subject.", null, ev));
        }
        if (!in.unpublishedTopics().isEmpty()) {
            List<String> next = in.unpublishedTopics().subList(0, Math.min(5, in.unpublishedTopics().size()));
            out.add(new Recommendation("FUTURE_TOPIC", "Approved subjects with no published video yet",
                    "These are in the approved syllabus: “" + String.join("”, “", next) + "”.",
                    "Approved topics of this project with no published lesson or quiz video.", null,
                    List.of(new Evidence("Approved topics without a video", String.valueOf(in.unpublishedTopics().size()), "Leenax approvals"))));
        }
    }

    // ---- content strategy --------------------------------------------------------------------------------

    static final Map<String, String> SOURCES = Map.ofEntries(
            Map.entry("YT_SEARCH", "YouTube search"), Map.entry("RELATED_VIDEO", "suggested videos"), Map.entry("SUBSCRIBER", "subscribers' feeds"),
            Map.entry("BROWSE", "browse features"), Map.entry("PLAYLIST", "playlists"), Map.entry("EXT_URL", "external websites"),
            Map.entry("NO_LINK_OTHER", "direct or unknown"), Map.entry("SHORTS", "the Shorts feed"), Map.entry("YT_CHANNEL", "channel pages"),
            Map.entry("END_SCREEN", "end screens"), Map.entry("NOTIFICATION", "notifications"), Map.entry("YT_OTHER_PAGE", "other YouTube pages"));

    static void strategy(Input in, List<Recommendation> out) {
        long total = in.trafficViews().values().stream().mapToLong(Long::longValue).sum();
        if (total >= MIN_VIEWS) {
            Map.Entry<String, Long> top = in.trafficViews().entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            double share = (double) top.getValue() / total;
            String name = SOURCES.getOrDefault(top.getKey(), top.getKey().toLowerCase(Locale.ROOT).replace('_', ' '));
            String advice = switch (top.getKey()) {
                case "YT_SEARCH" -> "Your viewers mostly arrive by searching. Keep titles keyword-first and make one video per question a student would type.";
                case "RELATED_VIDEO", "BROWSE" -> "YouTube recommends your videos. Series on one subject and strong end screens keep viewers moving from one to the next.";
                case "PLAYLIST" -> "Playlists bring your viewers. Keep each subject's playlist in syllabus order and add every new video to it.";
                case "EXT_URL", "NO_LINK_OTHER" -> "Most views come from links shared outside YouTube (a course page or a chat group). "
                        + "Search and suggestions are still small, so titles that match what students search for can widen the audience.";
                case "SHORTS" -> "The Shorts feed brings your viewers. Quiz videos in 9:16 suit it; link each Short to its full lesson.";
                default -> "Your largest source is " + name + ".";
            };
            out.add(new Recommendation("STRATEGY", "Where your viewers come from", advice,
                    "The traffic source with the most views of this project's videos in the period.", null,
                    List.of(new Evidence("Views from " + name, fmt(top.getValue()) + " of " + fmt(total) + " (" + pct(share) + ")", in.analyticsSource()))));
        }
        List<VideoStat> timed = in.videos().stream().filter(v -> v.averageViewDuration() != null && v.durationSeconds() != null
                && v.views() != null && v.views() >= MIN_VIEWS).toList();
        if (timed.size() >= 2) {
            double avgLen = timed.stream().mapToDouble(VideoStat::durationSeconds).average().orElse(0);
            double avgWatch = timed.stream().mapToDouble(VideoStat::averageViewDuration).average().orElse(0);
            if (avgLen > 0 && avgWatch / avgLen < 0.4) {
                out.add(new Recommendation("STRATEGY", "Viewers watch less than half of a typical video",
                        "Shorter lessons (around " + clock(Math.max(60, avgWatch * 1.5)) + ") or one idea per video may suit how your audience watches.",
                        "Average view duration below 40% of the average video length (videos with at least " + MIN_VIEWS + " views).", null,
                        List.of(new Evidence("Average length", clock(avgLen), "Leenax render"),
                                new Evidence("Average view duration", clock(avgWatch), in.analyticsSource()))));
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    static String pct(double fraction) {
        return String.format(Locale.ROOT, fraction < 0.1 ? "%.1f%%" : "%.0f%%", fraction * 100);
    }

    static String ratio(double r) {
        return String.format(Locale.ROOT, "%.2f", r);
    }

    static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    static String clock(double seconds) {
        long s = Math.round(seconds);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
