package com.leenax.studio.media;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Identifies uploads by their magic bytes, never by the filename or the
 * browser-supplied Content-Type. Anything not on the allow-list is rejected
 * (notably SVG, HTML and executables).
 */
public final class FileTypeSniffer {

    public record DetectedType(MediaKind kind, String contentType, String extension) {
    }

    /** Bytes needed for detection. */
    public static final int HEADER_BYTES = 64;

    private FileTypeSniffer() {
    }

    public static Optional<DetectedType> detect(byte[] h) {
        if (h == null || h.length < 4) {
            return Optional.empty();
        }
        // ---- images
        if (startsWith(h, 0xFF, 0xD8, 0xFF)) return of(MediaKind.IMAGE, "image/jpeg", "jpg");
        if (startsWith(h, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return of(MediaKind.IMAGE, "image/png", "png");
        if (ascii(h, 0, "GIF87a") || ascii(h, 0, "GIF89a")) return of(MediaKind.IMAGE, "image/gif", "gif");
        if (ascii(h, 0, "RIFF") && ascii(h, 8, "WEBP")) return of(MediaKind.IMAGE, "image/webp", "webp");

        // ---- ISO base media (MP4 / MOV / M4A)
        if (ascii(h, 4, "ftyp") && h.length >= 12) {
            String brand = new String(h, 8, 4, StandardCharsets.ISO_8859_1);
            return switch (brand) {
                case "qt  " -> of(MediaKind.VIDEO, "video/quicktime", "mov");
                case "M4A ", "M4B " -> of(MediaKind.AUDIO, "audio/mp4", "m4a");
                // HEIC/AVIF still images are not supported yet
                case "heic", "heix", "mif1", "msf1", "avif", "avis" -> Optional.empty();
                default -> of(MediaKind.VIDEO, "video/mp4", "mp4");
            };
        }

        // ---- Matroska / WebM
        if (startsWith(h, 0x1A, 0x45, 0xDF, 0xA3)) {
            boolean webm = indexOf(h, "webm".getBytes(StandardCharsets.ISO_8859_1)) >= 0;
            return webm ? of(MediaKind.VIDEO, "video/webm", "webm") : of(MediaKind.VIDEO, "video/x-matroska", "mkv");
        }

        // ---- audio
        if (ascii(h, 0, "RIFF") && ascii(h, 8, "WAVE")) return of(MediaKind.AUDIO, "audio/wav", "wav");
        if (ascii(h, 0, "fLaC")) return of(MediaKind.AUDIO, "audio/flac", "flac");
        if (ascii(h, 0, "OggS")) return of(MediaKind.AUDIO, "audio/ogg", "ogg");
        if (ascii(h, 0, "ID3")) return of(MediaKind.AUDIO, "audio/mpeg", "mp3");
        if ((h[0] & 0xFF) == 0xFF) {
            int b1 = h[1] & 0xFF;
            if (b1 == 0xF1 || b1 == 0xF9) return of(MediaKind.AUDIO, "audio/aac", "aac");
            if ((b1 & 0xE0) == 0xE0 && (b1 & 0x06) != 0) return of(MediaKind.AUDIO, "audio/mpeg", "mp3");
        }
        return Optional.empty();
    }

    private static Optional<DetectedType> of(MediaKind kind, String type, String ext) {
        return Optional.of(new DetectedType(kind, type, ext));
    }

    private static boolean startsWith(byte[] h, int... sig) {
        if (h.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if ((h[i] & 0xFF) != sig[i]) return false;
        }
        return true;
    }

    private static boolean ascii(byte[] h, int offset, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        if (h.length < offset + b.length) return false;
        return Arrays.equals(h, offset, offset + b.length, b, 0, b.length);
    }

    private static int indexOf(byte[] h, byte[] needle) {
        outer:
        for (int i = 0; i <= h.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (h[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
