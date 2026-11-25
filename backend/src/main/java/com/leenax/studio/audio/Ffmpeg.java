package com.leenax.studio.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs FFmpeg with a timeout and turns failures into a short, readable error. */
public final class Ffmpeg {

    public static final class FfmpegException extends RuntimeException {
        public FfmpegException(String message) {
            super(message);
        }
    }

    private final String binary;
    private final long timeoutSeconds;

    public Ffmpeg(String binary, long timeoutSeconds) {
        this.binary = binary;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Runs {@code ffmpeg -hide_banner -nostdin -y <args>}. */
    public void run(List<String> args) {
        List<String> cmd = new ArrayList<>(List.of(binary, "-hide_banner", "-nostdin", "-loglevel", "error", "-y"));
        cmd.addAll(args);
        Path log = null;
        try {
            log = Files.createTempFile("leenax-ffmpeg-", ".log");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new FfmpegException("Audio processing took too long and was stopped");
            }
            if (p.exitValue() != 0) {
                String out = Files.readString(log, StandardCharsets.UTF_8).strip();
                String last = out.isEmpty() ? "unknown error" : out.lines().reduce((a, b) -> b).orElse(out);
                throw new FfmpegException("FFmpeg failed: " + (last.length() > 200 ? last.substring(0, 200) : last));
            }
        } catch (IOException e) {
            throw new FfmpegException("FFmpeg is not available: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FfmpegException("Interrupted");
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // temp file
                }
            }
        }
    }
}
