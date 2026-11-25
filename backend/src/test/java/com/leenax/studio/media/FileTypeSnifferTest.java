package com.leenax.studio.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeSnifferTest {

    private static byte[] header(int... bytes) {
        byte[] out = new byte[64];
        for (int i = 0; i < bytes.length; i++) out[i] = (byte) bytes[i];
        return out;
    }

    private static byte[] withAscii(byte[] h, int offset, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(b, 0, h, offset, b.length);
        return h;
    }

    @Test
    void detectsJpegAndPng() {
        assertThat(FileTypeSniffer.detect(header(0xFF, 0xD8, 0xFF, 0xE0))).get()
                .extracting(FileTypeSniffer.DetectedType::contentType).isEqualTo("image/jpeg");
        assertThat(FileTypeSniffer.detect(header(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).get()
                .extracting(FileTypeSniffer.DetectedType::extension).isEqualTo("png");
    }

    @Test
    void detectsMp4MovAndM4a() {
        byte[] mp4 = withAscii(withAscii(new byte[64], 4, "ftyp"), 8, "isom");
        byte[] mov = withAscii(withAscii(new byte[64], 4, "ftyp"), 8, "qt  ");
        byte[] m4a = withAscii(withAscii(new byte[64], 4, "ftyp"), 8, "M4A ");
        assertThat(FileTypeSniffer.detect(mp4).orElseThrow().kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(FileTypeSniffer.detect(mov).orElseThrow().contentType()).isEqualTo("video/quicktime");
        assertThat(FileTypeSniffer.detect(m4a).orElseThrow().kind()).isEqualTo(MediaKind.AUDIO);
    }

    @Test
    void distinguishesWebmFromMkv() {
        byte[] webm = withAscii(header(0x1A, 0x45, 0xDF, 0xA3), 24, "webm");
        byte[] mkv = withAscii(header(0x1A, 0x45, 0xDF, 0xA3), 24, "matroska");
        assertThat(FileTypeSniffer.detect(webm).orElseThrow().extension()).isEqualTo("webm");
        assertThat(FileTypeSniffer.detect(mkv).orElseThrow().extension()).isEqualTo("mkv");
    }

    @Test
    void detectsWavWebpAndMp3() {
        byte[] wav = withAscii(withAscii(new byte[64], 0, "RIFF"), 8, "WAVE");
        byte[] webp = withAscii(withAscii(new byte[64], 0, "RIFF"), 8, "WEBP");
        assertThat(FileTypeSniffer.detect(wav).orElseThrow().kind()).isEqualTo(MediaKind.AUDIO);
        assertThat(FileTypeSniffer.detect(webp).orElseThrow().kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(FileTypeSniffer.detect(withAscii(new byte[64], 0, "ID3")).orElseThrow().extension()).isEqualTo("mp3");
        assertThat(FileTypeSniffer.detect(header(0xFF, 0xFB, 0x90, 0x00)).orElseThrow().extension()).isEqualTo("mp3");
    }

    @Test
    void rejectsSvgHtmlExecutablesAndHeic() {
        assertThat(FileTypeSniffer.detect(withAscii(new byte[64], 0, "<svg xmlns="))).isEmpty();
        assertThat(FileTypeSniffer.detect(withAscii(new byte[64], 0, "<!DOCTYPE html>"))).isEmpty();
        assertThat(FileTypeSniffer.detect(header('M', 'Z', 0x90, 0x00))).isEmpty();
        assertThat(FileTypeSniffer.detect(withAscii(withAscii(new byte[64], 4, "ftyp"), 8, "heic"))).isEmpty();
        assertThat(FileTypeSniffer.detect(Arrays.copyOf(new byte[]{1, 2}, 2))).isEmpty();
    }
}
