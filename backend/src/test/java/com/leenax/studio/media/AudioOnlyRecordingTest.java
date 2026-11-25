package com.leenax.studio.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Browser microphone recordings are WebM/MP4 with sound only and must be stored as audio. */
class AudioOnlyRecordingTest {

    private final MediaProbe probe = new MediaProbe("ffprobe", "ffmpeg", new ObjectMapper());

    private MediaProbe.Metadata parse(String json) throws Exception {
        return probe.parse(new ObjectMapper().readTree(json), MediaKind.VIDEO);
    }

    @Test
    void webmWithOnlyOpusBecomesAudio() throws Exception {
        var meta = parse("{\"streams\":[{\"codec_type\":\"audio\",\"codec_name\":\"opus\"}],\"format\":{\"duration\":\"12.5\"}}");
        var type = MediaService.audioOnlyContainer(new FileTypeSniffer.DetectedType(MediaKind.VIDEO, "video/webm", "webm"), meta);
        assertEquals(MediaKind.AUDIO, type.kind());
        assertEquals("audio/webm", type.contentType());
        assertEquals(Long.valueOf(12500), meta.durationMs());
        assertNull(meta.width());
    }

    @Test
    void coverArtIsNotAVideo() throws Exception {
        var meta = parse("{\"streams\":[{\"codec_type\":\"audio\"},{\"codec_type\":\"video\",\"width\":600,\"height\":600,"
                + "\"disposition\":{\"attached_pic\":1}}],\"format\":{\"duration\":\"3\"}}");
        var type = MediaService.audioOnlyContainer(new FileTypeSniffer.DetectedType(MediaKind.VIDEO, "video/mp4", "mp4"), meta);
        assertEquals(MediaKind.AUDIO, type.kind());
        assertEquals("m4a", type.extension());
    }

    @Test
    void realVideosAndUnknownMetadataStayVideo() throws Exception {
        var meta = parse("{\"streams\":[{\"codec_type\":\"video\",\"width\":1920,\"height\":1080},{\"codec_type\":\"audio\"}],\"format\":{}}");
        var mp4 = new FileTypeSniffer.DetectedType(MediaKind.VIDEO, "video/mp4", "mp4");
        assertEquals(MediaKind.VIDEO, MediaService.audioOnlyContainer(mp4, meta).kind());
        assertEquals(Integer.valueOf(1920), meta.width());
        assertEquals(MediaKind.VIDEO, MediaService.audioOnlyContainer(mp4, MediaProbe.Metadata.EMPTY).kind());
    }
}
