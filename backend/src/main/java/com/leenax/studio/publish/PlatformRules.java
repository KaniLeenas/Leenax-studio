package com.leenax.studio.publish;

import com.leenax.studio.social.SocialPlatform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks and cleans one platform's post settings before anything is saved,
 * using the limits the official APIs document. Only settings the API really
 * supports are accepted.
 */
public final class PlatformRules {

    private PlatformRules() {
    }

    /** What the publisher knows about the video file. */
    public record MediaFacts(long sizeBytes, Long durationMs, Integer width, Integer height) {
        double seconds() {
            return durationMs == null ? -1 : durationMs / 1000.0;
        }
    }

    public static final Set<String> YOUTUBE_PRIVACY = Set.of("public", "unlisted", "private");
    public static final Set<String> TIKTOK_PRIVACY = Set.of("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "FOLLOWER_OF_CREATOR", "SELF_ONLY");
    private static final Pattern HASHTAG = Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}_])#[\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}_]*");

    /** Returns clean settings; adds a message to {@code errors} for each problem. */
    public static Map<String, Object> normalize(SocialPlatform platform, Map<String, Object> in, MediaFacts media,
                                                List<String> errors) {
        Map<String, Object> s = in == null ? Map.of() : in;
        List<String> e = new ArrayList<>();
        Map<String, Object> out = switch (platform) {
            case YOUTUBE -> youtube(s, e);
            case FACEBOOK -> facebook(s, media, e);
            case INSTAGRAM -> instagram(s, media, e);
            case TIKTOK -> tiktok(s, media, e);
        };
        for (String msg : e) errors.add(platform.displayName() + ": " + msg);
        return out;
    }

    static Map<String, Object> youtube(Map<String, Object> s, List<String> e) {
        Map<String, Object> o = new LinkedHashMap<>();
        String title = str(s, "title");
        if (title.isBlank()) e.add("a title is required");
        else if (title.length() > 100) e.add("the title can be at most 100 characters");
        if (title.contains("<") || title.contains(">")) e.add("the title cannot contain < or >");
        o.put("title", title);
        String description = str(s, "description");
        if (description.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 5000) e.add("the description can be at most 5000 bytes");
        if (description.contains("<") || description.contains(">")) e.add("the description cannot contain < or >");
        o.put("description", description);
        List<String> tags = new ArrayList<>();
        int tagChars = 0;
        for (String t : strings(s.get("tags"))) {
            String tag = t.strip().replaceAll("^#", "");
            if (tag.isEmpty() || tags.contains(tag)) continue;
            tags.add(tag);
            tagChars += tag.length() + (tag.contains(" ") ? 2 : 0) + (tags.size() > 1 ? 1 : 0);
        }
        if (tagChars > 500) e.add("tags can be at most 500 characters in total");
        o.put("tags", tags);
        String category = str(s, "categoryId");
        o.put("categoryId", category.matches("\\d{1,3}") ? category : "22");   // 22 = People & Blogs
        String privacy = str(s, "privacy");
        if (!YOUTUBE_PRIVACY.contains(privacy)) e.add("choose public, unlisted or private");
        o.put("privacy", privacy);
        // "Scheduled" on YouTube: uploaded private, made public by YouTube at publishAt (E9)
        String publishAt = str(s, "publishAt");
        if (!publishAt.isBlank()) {
            try {
                java.time.Instant at = java.time.Instant.parse(publishAt);
                if (!"private".equals(privacy)) e.add("a scheduled video is uploaded as private until its publish time");
                if (at.isBefore(java.time.Instant.now().plusSeconds(15 * 60))) e.add("the publish time must be at least 15 minutes ahead");
                if (at.isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(365)))) e.add("the publish time can be at most a year ahead");
                o.put("publishAt", at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
            } catch (java.time.format.DateTimeParseException x) {
                e.add("the publish time isn't a valid date");
            }
        }
        if (!(s.get("madeForKids") instanceof Boolean)) e.add("say whether the video is made for kids (YouTube requires it)");
        o.put("madeForKids", bool(s, "madeForKids"));
        o.put("aiGenerated", bool(s, "aiGenerated"));
        o.put("notifySubscribers", !Boolean.FALSE.equals(s.get("notifySubscribers")));
        // E9: playlists to add the video to, and caption files to upload with it
        List<String> playlists = new ArrayList<>();
        for (String id : strings(s.get("playlistIds"))) {
            String p = id.strip();
            if (!p.matches("[A-Za-z0-9_-]{10,64}")) e.add("a playlist id isn't valid");
            else if (!playlists.contains(p)) playlists.add(p);
        }
        if (playlists.size() > 5) e.add("a video can be added to at most 5 playlists at once");
        if (!playlists.isEmpty()) o.put("playlistIds", playlists);
        if (s.get("captions") instanceof List<?> caps && !caps.isEmpty()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object c : caps) {
                if (!(c instanceof Map<?, ?> m)) continue;
                String lang = String.valueOf(m.get("language") == null ? "" : m.get("language")).strip();
                String name = String.valueOf(m.get("name") == null ? "" : m.get("name")).strip();
                String srt = String.valueOf(m.get("srt") == null ? "" : m.get("srt"));
                if (!lang.matches("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?")) e.add("a caption file needs its language (like en or ta)");
                if (name.length() > 100) e.add("a caption track name can be at most 100 characters");
                if (srt.isBlank() || srt.length() > 200_000) e.add("a caption file is empty or too long");
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("language", lang);
                cm.put("name", name);
                cm.put("srt", srt);
                out.add(cm);
            }
            if (out.size() > 3) e.add("at most 3 caption files");
            o.put("captions", out);
        }
        return o;
    }

    static Map<String, Object> facebook(Map<String, Object> s, MediaFacts m, List<String> e) {
        Map<String, Object> o = new LinkedHashMap<>();
        String description = str(s, "description");
        if (description.length() > 5000) e.add("the description can be at most 5000 characters");
        o.put("description", description);
        String title = str(s, "title");
        if (title.length() > 255) e.add("the title can be at most 255 characters");
        o.put("title", title);
        double sec = m.seconds();
        if (sec >= 0 && (sec < 3 || sec > 90)) e.add("Page reels must be 3 to 90 seconds long (this video is " + Math.round(sec) + " s)");
        if (m.width() != null && m.height() != null && (Math.min(m.width(), m.height()) < 540 || Math.max(m.width(), m.height()) < 960)) {
            e.add("Page reels need at least 540 × 960 pixels");
        }
        return o;
    }

    static Map<String, Object> instagram(Map<String, Object> s, MediaFacts m, List<String> e) {
        Map<String, Object> o = new LinkedHashMap<>();
        String caption = str(s, "caption");
        if (caption.length() > 2200) e.add("the caption can be at most 2200 characters");
        if (hashtags(caption) > 30) e.add("a caption can have at most 30 hashtags");
        o.put("caption", caption);
        o.put("shareToFeed", !Boolean.FALSE.equals(s.get("shareToFeed")));
        Double cover = number(s, "coverSeconds");
        if (cover != null) o.put("coverSeconds", Math.max(0, cover));
        double sec = m.seconds();
        if (sec >= 0 && (sec < 3 || sec > 900)) e.add("reels must be 3 seconds to 15 minutes long");
        if (m.sizeBytes() > 300L * 1024 * 1024) e.add("reels can be at most 300 MB (export a smaller file)");
        return o;
    }

    static Map<String, Object> tiktok(Map<String, Object> s, MediaFacts m, List<String> e) {
        Map<String, Object> o = new LinkedHashMap<>();
        String mode = str(s, "mode").isBlank() ? "DIRECT" : str(s, "mode");
        if (!mode.equals("DIRECT") && !mode.equals("INBOX")) e.add("choose post now or send to the TikTok app");
        o.put("mode", mode);
        String caption = str(s, "caption");
        if (caption.length() > 2200) e.add("the caption can be at most 2200 characters");   // UTF-16 units, like TikTok counts
        o.put("caption", caption);
        Double cover = number(s, "coverSeconds");
        if (cover != null) o.put("coverSeconds", Math.max(0, cover));
        if (mode.equals("INBOX")) return o;

        String privacy = str(s, "privacy");
        if (privacy.isBlank()) e.add("choose who can see the post (TikTok does not allow a default)");
        else if (!TIKTOK_PRIVACY.contains(privacy)) e.add("unknown privacy option");
        o.put("privacy", privacy);
        o.put("allowComment", bool(s, "allowComment"));
        o.put("allowDuet", bool(s, "allowDuet"));
        o.put("allowStitch", bool(s, "allowStitch"));
        boolean brandContent = bool(s, "brandContent");
        boolean brandOrganic = bool(s, "brandOrganic");
        if (brandContent && "SELF_ONLY".equals(privacy)) e.add("branded content cannot be private (only me)");
        o.put("brandContent", brandContent);
        o.put("brandOrganic", brandOrganic);
        o.put("aiGenerated", bool(s, "aiGenerated"));
        if (!bool(s, "musicConsent")) e.add("agree to TikTok's Music Usage Confirmation" + (brandContent ? " and Branded Content Policy" : ""));
        o.put("musicConsent", true);
        return o;
    }

    public static int hashtags(String text) {
        Matcher m = HASHTAG.matcher(text == null ? "" : text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static String str(Map<String, Object> s, String key) {
        Object v = s.get(key);
        return v == null ? "" : v.toString().strip();
    }

    static boolean bool(Map<String, Object> s, String key) {
        return Boolean.TRUE.equals(s.get(key));
    }

    static Double number(Map<String, Object> s, String key) {
        Object v = s.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String str && !str.isBlank()) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object x : l) if (x != null) out.add(x.toString());
        } else if (v instanceof String str) {
            for (String part : str.split(",")) out.add(part);
        }
        return out;
    }
}
