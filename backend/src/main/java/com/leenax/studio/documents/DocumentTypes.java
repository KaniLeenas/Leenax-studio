package com.leenax.studio.documents;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Optional;

/**
 * Which course documents are accepted, judged by their first bytes (and, for the
 * Office formats that share a container, the file name). The document service
 * checks again before reading.
 */
public final class DocumentTypes {

    public record Type(String contentType, String extension, String label) {
    }

    static final int HEADER_BYTES = 8192;
    public static final String ACCEPTED = "PDF, Word (DOCX, DOC), PowerPoint (PPTX, PPT), OpenDocument (ODT, ODP), plain text, or a photo or scan (JPG, PNG, TIFF, WebP)";

    private DocumentTypes() {
    }

    public static Optional<Type> detect(byte[] h, String filename) {
        if (h == null || h.length < 4) return Optional.empty();
        String ext = extension(filename);
        if (ascii(h, 0, "%PDF-")) return of("application/pdf", "pdf", "PDF");
        if (h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4) {
            String head = new String(h, StandardCharsets.ISO_8859_1);
            if (head.contains("word/")) return of("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx", "Word");
            if (head.contains("ppt/")) return of("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx", "PowerPoint");
            if (head.contains("opendocument.text")) return of("application/vnd.oasis.opendocument.text", "odt", "OpenDocument text");
            if (head.contains("opendocument.presentation")) return of("application/vnd.oasis.opendocument.presentation", "odp", "OpenDocument slides");
            return switch (ext) {
                case "docx" -> of("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx", "Word");
                case "pptx" -> of("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx", "PowerPoint");
                default -> Optional.empty();
            };
        }
        if (unsigned(h, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            return switch (ext) {
                case "doc" -> of("application/msword", "doc", "Word");
                case "ppt" -> of("application/vnd.ms-powerpoint", "ppt", "PowerPoint");
                default -> Optional.empty();
            };
        }
        if (unsigned(h, 0xFF, 0xD8, 0xFF)) return of("image/jpeg", "jpg", "Photo");
        if (unsigned(h, 0x89, 'P', 'N', 'G')) return of("image/png", "png", "Image");
        if (ascii(h, 0, "II*\0") || ascii(h, 0, "MM\0*")) return of("image/tiff", "tiff", "Scan");
        if (ascii(h, 0, "RIFF") && ascii(h, 8, "WEBP")) return of("image/webp", "webp", "Image");
        if ((ext.equals("txt") || ext.equals("md")) && isUtf8Text(h)) return of("text/plain; charset=utf-8", ext, "Text");
        return Optional.empty();
    }

    static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** No NUL bytes and valid UTF-8, allowing a character cut off at the end of the sample. */
    static boolean isUtf8Text(byte[] h) {
        for (byte b : h) if (b == 0) return false;
        int len = h.length;
        for (int trim = 0; trim < 4 && len - trim > 0; trim++) {
            try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(h, 0, len - trim));
                return true;
            } catch (CharacterCodingException e) {
                // a multi-byte character may straddle the sample's end
            }
        }
        return false;
    }

    private static Optional<Type> of(String type, String ext, String label) {
        return Optional.of(new Type(type, ext, label));
    }

    private static boolean ascii(byte[] h, int offset, String s) {
        if (h.length < offset + s.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (h[offset + i] != (byte) s.charAt(i)) return false;
        }
        return true;
    }

    private static boolean unsigned(byte[] h, int... sig) {
        if (h.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if ((h[i] & 0xFF) != sig[i]) return false;
        }
        return true;
    }
}
