package com.leenax.studio.publish;

import com.leenax.studio.ai.provider.MediaTypes;
import com.leenax.studio.social.SocialAccount;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/** Everything a publisher needs for one target, with lazily fetched token and file. */
public class Job {

    public interface Saver {
        void save(Map<String, Object> state, String step, double progress);
    }

    private final SocialAccount account;
    private final Map<String, Object> settings;
    private final Map<String, Object> state;
    private final long size;
    private final String contentType;
    private final Long durationMs;
    private final Supplier<String> token;
    private final Supplier<Path> file;
    private final Supplier<MediaTypes.Sample> thumbnail;
    private final Saver saver;
    private Path localFile;

    public Job(SocialAccount account, Map<String, Object> settings, Map<String, Object> state, long size,
               String contentType, Long durationMs, Supplier<String> token, Supplier<Path> file,
               Supplier<MediaTypes.Sample> thumbnail, Saver saver) {
        this.account = account;
        this.settings = settings;
        this.state = state;
        this.size = size;
        this.contentType = contentType == null ? "video/mp4" : contentType;
        this.durationMs = durationMs;
        this.token = token;
        this.file = file;
        this.thumbnail = thumbnail;
        this.saver = saver;
    }

    public SocialAccount account() { return account; }
    public Map<String, Object> settings() { return settings; }
    public Map<String, Object> state() { return state; }
    public long size() { return size; }
    public String contentType() { return contentType; }
    public Long durationMs() { return durationMs; }

    /** A fresh access token (refreshed when close to expiry). */
    public String token() {
        return token.get();
    }

    /** The video on local disk, downloaded from storage on first use. */
    public Path video() {
        if (localFile == null) localFile = file.get();
        return localFile;
    }

    /** The chosen thumbnail image, or null when none was chosen. */
    public MediaTypes.Sample thumbnail() {
        return thumbnail == null ? null : thumbnail.get();
    }

    /** Persists {@link #state()} with a progress note. */
    public void checkpoint(String step, double progress) {
        saver.save(state, step, progress);
    }

    public String setting(String key) {
        Object v = settings.get(key);
        return v == null ? "" : v.toString();
    }

    public boolean setting(String key, boolean fallback) {
        Object v = settings.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    /** Whether a step was already completed in an earlier attempt. */
    public boolean done(String key) {
        return Boolean.TRUE.equals(state.get(key));
    }

    public String stateText(String key) {
        Object v = state.get(key);
        return v == null ? null : v.toString();
    }

    /** Remembers when processing began, for time-outs. */
    public Instant since(String key) {
        Object v = state.get(key);
        if (v == null) {
            Instant now = Instant.now();
            state.put(key, now.toString());
            return now;
        }
        return Instant.parse(v.toString());
    }

    public void cleanup() {
        if (localFile != null) {
            try {
                Files.deleteIfExists(localFile);
            } catch (Exception ignored) {
                // temp dir is cleaned on restart anyway
            }
        }
    }
}
