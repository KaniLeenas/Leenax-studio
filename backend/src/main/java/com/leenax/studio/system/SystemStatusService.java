package com.leenax.studio.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.media.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One page of health: services, background queues, storage and backups, with
 * plain-language warnings. Read-only and cheap enough to poll every minute.
 */
@Service
public class SystemStatusService {

    public enum State { OK, WARNING, DOWN, OFF }

    public record Check(String name, State state, String detail) {
    }

    public record Queue(String name, long waiting, long running, long failedToday) {
    }

    public record Backup(Instant finishedAt, boolean ok, Long databaseBytes, Long mediaBytes, String folder, String error) {
    }

    public record Disk(String path, long totalBytes, long freeBytes) {
    }

    public record Status(Instant checkedAt, Duration uptime, String javaVersion, String schemaVersion, List<Check> services,
                         List<Queue> queues, long mediaFiles, long mediaBytes, Long databaseBytes, Backup lastBackup,
                         Disk backupDisk, List<String> warnings) {
    }

    static final Duration BACKUP_STALE = Duration.ofHours(36);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final String rendererUrl;
    private final String imagetoolsUrl;
    private final Path backupDir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public SystemStatusService(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectStorage storage, ObjectMapper mapper,
                               @Value("${leenax.renderer.url:}") String rendererUrl,
                               @Value("${leenax.imagetools.url:}") String imagetoolsUrl,
                               @Value("${leenax.backup.dir:/backups}") String backupDir) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.storage = storage;
        this.mapper = mapper;
        this.rendererUrl = rendererUrl;
        this.imagetoolsUrl = imagetoolsUrl;
        this.backupDir = Path.of(backupDir);
    }

    public Status status() {
        List<Check> services = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String schema = null;
        Long dbBytes = null;
        try {
            schema = jdbc.queryForObject("SELECT max(version) FROM flyway_schema_history WHERE success", String.class);
            dbBytes = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
            services.add(new Check("Database", State.OK, "PostgreSQL, schema version " + schema));
        } catch (RuntimeException e) {
            services.add(new Check("Database", State.DOWN, "Not reachable"));
        }
        try {
            redis.hasKey("leenax:health");
            services.add(new Check("Sessions (Redis)", State.OK, "Reachable"));
        } catch (RuntimeException e) {
            services.add(new Check("Sessions (Redis)", State.DOWN, "Not reachable: sign-in and sessions will fail"));
        }
        services.add(storage.reachable() ? new Check("Media storage", State.OK, "Bucket reachable")
                : new Check("Media storage", State.DOWN, "Bucket not reachable: uploads and playback will fail"));
        services.add(probe("Video export service", rendererUrl, "Exports are off (RENDERER_URL not set)"));
        services.add(probe("Background removal service", imagetoolsUrl, "Background removal is off (IMAGETOOLS_URL not set)"));
        for (Check c : services) if (c.state() == State.DOWN) warnings.add(c.name() + " is down.");

        List<Queue> queues = new ArrayList<>();
        queue(queues, "AI generations", "generation_jobs", "'QUEUED'", "'PROCESSING'", "coalesce(completed_at, created_at)");
        queue(queues, "Video exports", "render_jobs", "'QUEUED'", "'RENDERING'", "updated_at");
        queue(queues, "Automatic edits", "auto_edit_jobs", "'QUEUED'", "'RUNNING'", "updated_at");
        queue(queues, "Social posts", "post_targets", "'SCHEDULED'", "'UPLOADING','PROCESSING'", "updated_at");
        long failed = queues.stream().mapToLong(Queue::failedToday).sum();
        if (failed > 0) warnings.add(failed + " background job" + (failed == 1 ? "" : "s") + " failed in the last 24 hours.");

        long mediaFiles = 0;
        long mediaBytes = 0;
        try {
            Long[] m = jdbc.queryForObject("SELECT count(*), coalesce(sum(size_bytes), 0) FROM media_assets",
                    (rs, i) -> new Long[]{rs.getLong(1), rs.getLong(2)});
            mediaFiles = m[0];
            mediaBytes = m[1];
            Long reconnect = jdbc.queryForObject("SELECT count(*) FROM social_accounts WHERE status = 'NEEDS_RECONNECT'", Long.class);
            if (reconnect != null && reconnect > 0) warnings.add(reconnect + " social account" + (reconnect == 1 ? " needs" : "s need") + " reconnecting.");
            Long invalidKeys = jdbc.queryForObject("SELECT count(*) FROM ai_accounts WHERE status = 'INVALID'", Long.class);
            if (invalidKeys != null && invalidKeys > 0) warnings.add(invalidKeys + " AI account key" + (invalidKeys == 1 ? " was" : "s were") + " rejected by the provider.");
        } catch (RuntimeException ignored) {
            // database already reported as down
        }

        Backup backup = readBackup(backupDir);
        Disk disk = disk(backupDir);
        warnings.addAll(backupWarnings(backup, disk, Instant.now()));

        return new Status(Instant.now(), Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()),
                System.getProperty("java.version"), schema, services, queues, mediaFiles, mediaBytes, dbBytes, backup, disk, warnings);
    }

    private void queue(List<Queue> out, String name, String table, String waiting, String running, String updated) {
        try {
            Long[] c = jdbc.queryForObject("SELECT count(*) FILTER (WHERE status IN (" + waiting + ")), "
                            + "count(*) FILTER (WHERE status IN (" + running + ")), "
                            + "count(*) FILTER (WHERE status = 'FAILED' AND " + updated + " > now() - interval '24 hours') FROM " + table,
                    (rs, i) -> new Long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});
            out.add(new Queue(name, c[0], c[1], c[2]));
        } catch (RuntimeException e) {
            out.add(new Queue(name, -1, -1, 0));
        }
    }

    private Check probe(String name, String baseUrl, String offText) {
        if (baseUrl == null || baseUrl.isBlank()) return new Check(name, State.OFF, offText);
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/health"))
                    .timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200 ? new Check(name, State.OK, "Running") : new Check(name, State.DOWN, "Answered HTTP " + r.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Check(name, State.DOWN, "Not reachable");
        }
    }

    /** The backup service writes latest.json after each run (see deploy/backup-loop.sh). */
    Backup readBackup(Path dir) {
        Path f = dir.resolve("latest.json");
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonNode j = mapper.readTree(f.toFile());
            return new Backup(j.hasNonNull("finishedAt") ? Instant.parse(j.get("finishedAt").asText()) : null,
                    j.path("ok").asBoolean(false),
                    j.hasNonNull("databaseBytes") ? j.get("databaseBytes").asLong() : null,
                    j.hasNonNull("mediaBytes") ? j.get("mediaBytes").asLong() : null,
                    j.path("folder").asText(null), j.path("error").asText(null));
        } catch (Exception e) {
            return new Backup(null, false, null, null, null, "latest.json could not be read");
        }
    }

    static Disk disk(Path dir) {
        try {
            if (!Files.isDirectory(dir)) return null;
            var store = Files.getFileStore(dir);
            return new Disk(dir.toString(), store.getTotalSpace(), store.getUsableSpace());
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> backupWarnings(Backup b, Disk disk, Instant now) {
        List<String> w = new ArrayList<>();
        if (b == null) w.add("No automatic backup found yet. Start the backup service (see README, Backups).");
        else if (!b.ok()) w.add("The last backup failed" + (b.error() != null ? ": " + b.error() : "."));
        else if (b.finishedAt() == null || b.finishedAt().plus(BACKUP_STALE).isBefore(now)) w.add("The last good backup is more than a day old.");
        if (disk != null && disk.totalBytes() > 0 && disk.freeBytes() < disk.totalBytes() / 10) {
            w.add("The backup disk is over 90% full.");
        }
        return w;
    }
}
