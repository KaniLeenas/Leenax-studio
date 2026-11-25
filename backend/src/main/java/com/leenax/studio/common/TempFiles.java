package com.leenax.studio.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Collects temp files for one operation and deletes them all at the end (try-with-resources). */
public final class TempFiles implements AutoCloseable {

    private final List<Path> paths = new ArrayList<>();

    public Path create(String suffix) {
        try {
            Path p = Files.createTempFile("leenax-", suffix);
            paths.add(p);
            return p;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not create a working file");
        }
    }

    public Path write(byte[] bytes, String suffix) {
        Path p = create(suffix);
        try {
            Files.write(p, bytes);
            return p;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not write a working file");
        }
    }

    /** Takes ownership of a file created elsewhere. */
    public Path track(Path p) {
        paths.add(p);
        return p;
    }

    @Override
    public void close() {
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // temp file
            }
        }
    }

    public static String extensionFor(String mimeType) {
        if (mimeType == null) return ".bin";
        return switch (mimeType.toLowerCase()) {
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> ".wav";
            case "audio/ogg" -> ".ogg";
            case "audio/webm" -> ".webm";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            case "audio/flac" -> ".flac";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }
}
