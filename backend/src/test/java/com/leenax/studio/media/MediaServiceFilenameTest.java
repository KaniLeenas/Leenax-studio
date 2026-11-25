package com.leenax.studio.media;

import com.leenax.studio.media.FileTypeSniffer.DetectedType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaServiceFilenameTest {

    private static final DetectedType MP4 = new DetectedType(MediaKind.VIDEO, "video/mp4", "mp4");

    @Test
    void forcesExtensionToMatchRealContent() {
        assertThat(MediaService.cleanFilename("holiday.exe", MP4)).isEqualTo("holiday.mp4");
        assertThat(MediaService.cleanFilename("clip", MP4)).isEqualTo("clip.mp4");
    }

    @Test
    void stripsPathsAndUnsafeCharacters() {
        assertThat(MediaService.cleanFilename("C:\\Users\\kani\\Videos\\vlog.mov", MP4)).isEqualTo("vlog.mp4");
        assertThat(MediaService.cleanFilename("../../etc/passwd", MP4)).isEqualTo("passwd.mp4");
        assertThat(MediaService.cleanFilename("a\"b<c>.mp4", MP4)).isEqualTo("a_b_c_.mp4");
    }

    @Test
    void keepsTamilNames() {
        assertThat(MediaService.cleanFilename("பயணம்.mp4", MP4)).isEqualTo("பயணம்.mp4");
    }

    @Test
    void handlesMissingName() {
        assertThat(MediaService.cleanFilename(null, MP4)).isEqualTo("upload.mp4");
        assertThat(MediaService.cleanFilename(".mp4", MP4)).isEqualTo("upload.mp4");
    }
}
