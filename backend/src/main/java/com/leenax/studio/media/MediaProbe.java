package com.leenax.studio.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Reads media metadata with ffprobe and extracts poster frames with ffmpeg.
 * Both binaries ship in the backend Docker image. If they are missing, uploads
 * still succeed — just without dimensions, duration or poster.
 */
@Component
public class MediaProbe {

    private static final Logger log = LoggerFactory.getLogger(MediaProbe.class);
    private static final long TIMEOUT_SECONDS = 60;

    /**
     * @param hasVideo whether a real picture stream exists; {@code null} when ffprobe could not tell.
     *                 Browser microphone recordings are WebM/MP4 containers with audio only.
     */
    public record Metadata(Integer width, Integer height, Long durationMs, Boolean hasVideo, Boolean hasAudio) {
        static final Metadata EMPTY = new Metadata(null, null, null, null, null);

        public Metadata(Integer width, Integer height, Long durationMs) {
            this(width, height, durationMs, null, null);
        }
    }

    private final String ffprobe;
    private final String ffmpeg;
    private final ObjectMapper mapper;

    public MediaProbe(@Value("${leenax.media.ffprobe-path:ffprobe}") String ffprobe,
                      @Value("${leenax.media.ffmpeg-path:ffmpeg}") String ffmpeg,
                      ObjectMapper mapper) {
        this.ffprobe = ffprobe;
        this.ffmpeg = ffmpeg;
        this.mapper = mapper;
    }

    public Metadata probe(Path file, MediaKind kind) {
        Path out = null;
        try {
            out = Files.createTempFile("leenax-probe-", ".json");
            boolean ok = run(List.of(ffprobe, "-v", "error", "-print_format", "json",
                    "-show_format", "-show_streams", file.toString()), out);
            if (!ok) return Metadata.EMPTY;
            return parse(mapper.readTree(out.toFile()), kind);
        } catch (IOException e) {
            log.warn("ffprobe unavailable or failed: {}", e.getMessage());
            return Metadata.EMPTY;
        } finally {
            deleteQuietly(out);
        }
    }

    /** Extracts a JPEG poster frame (max 640 px wide). Returns the temp file, or empty on failure. */
    public Optional<Path> posterFrame(Path video, Long durationMs) {
        Path poster = null;
        try {
            poster = Files.createTempFile("leenax-poster-", ".jpg");
            String seek = durationMs != null && durationMs > 2000 ? "1" : "0";
            List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-v", "error", "-y", "-ss", seek, "-i", video.toString(),
                    "-frames:v", "1", "-vf", "scale='min(640,iw)':-2", "-q:v", "4", poster.toString()));
            if (run(cmd, null) && Files.size(poster) > 0) {
                return Optional.of(poster);
            }
        } catch (IOException e) {
            log.warn("ffmpeg unavailable or failed: {}", e.getMessage());
        }
        deleteQuietly(poster);
        return Optional.empty();
    }

    Metadata parse(JsonNode root, MediaKind kind) {
        Integer width = null;
        Integer height = null;
        boolean video = false;
        boolean audio = false;
        for (JsonNode stream : root.path("streams")) {
            String type = stream.path("codec_type").asText();
            if ("audio".equals(type)) audio = true;
            // cover art in an MP3/M4A is a video stream flagged as an attached picture
            boolean attachedPic = stream.path("disposition").path("attached_pic").asInt(0) == 1;
            if ("video".equals(type) && !attachedPic && !video) {
                video = true;
                width = stream.hasNonNull("width") ? stream.get("width").asInt() : null;
                height = stream.hasNonNull("height") ? stream.get("height").asInt() : null;
            }
        }
        Long durationMs = null;
        if (kind != MediaKind.IMAGE) {
            String d = root.path("format").path("duration").asText(null);
            if (d != null) {
                try {
                    durationMs = Math.round(Double.parseDouble(d) * 1000);
                } catch (NumberFormatException ignored) {
                    // leave null
                }
            }
        }
        if (kind == MediaKind.IMAGE) return new Metadata(width, height, null, null, null);
        return new Metadata(video ? width : null, video ? height : null, durationMs, video, audio);
    }

    private boolean run(List<String> command, Path stdout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectOutput(stdout != null ? ProcessBuilder.Redirect.to(stdout.toFile()) : ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("{} timed out", command.get(0));
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp file; the OS cleans up eventually
        }
    }
}
