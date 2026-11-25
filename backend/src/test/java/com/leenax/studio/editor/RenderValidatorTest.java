package com.leenax.studio.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real files made with ffmpeg, checked with the real ffprobe. */
class RenderValidatorTest {

    final RenderValidator v = new RenderValidator("ffprobe", "ffmpeg", new ObjectMapper());

    static Path make(String name, double seconds, int w, int h, int fps, String audio, String codec, String pix) throws Exception {
        Path dir = Files.createTempDirectory("leenax-validate-");
        Path out = dir.resolve(name);
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=" + w + "x" + h + ":rate=" + fps + ":duration=" + seconds));
        if (audio != null) cmd.addAll(List.of("-f", "lavfi", "-i", audio + ":duration=" + seconds));
        cmd.addAll(List.of("-c:v", codec, "-pix_fmt", pix));
        if (audio != null) cmd.addAll(List.of("-c:a", "aac", "-shortest"));
        cmd.add(out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0, "ffmpeg made " + name);
        return out;
    }

    @Test
    void aGoodRenderPasses() throws Exception {
        Path f = make("good.mp4", 4, 640, 360, 30, "sine=frequency=440:sample_rate=48000", "libx264", "yuv420p");
        RenderValidator.Result r = v.validate(f, new RenderValidator.Expected(4.0, 640, 360, 30, true));
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(120L, r.found().get("frames"));
        assertEquals("aac", r.found().get("audioCodec"));
        assertTrue((Double) r.found().get("meanVolumeDb") > -40);
        assertEquals(true, r.toMap().get("ok"));
    }

    @Test
    void silenceWrongSizeLengthAndFrameRateAreCaught() throws Exception {
        Path silent = make("silent.mp4", 3, 640, 360, 25, "anullsrc=r=48000:cl=stereo", "libx264", "yuv420p");
        RenderValidator.Result r = v.validate(silent, new RenderValidator.Expected(4.0, 1280, 720, 30, true));
        assertFalse(r.ok());
        String all = String.join(" | ", r.problems());
        assertTrue(all.contains("The sound is silent"), all);
        assertTrue(all.contains("640×360; expected 1280×720"), all);
        assertTrue(all.contains("frame rate is 25.00; expected 30"), all);
        assertTrue(all.contains("3.00 s long; the timeline is 4.00 s"), all);
    }

    @Test
    void missingSoundAndTheWrongPictureFormatAreCaught() throws Exception {
        Path f = make("nosound.mp4", 2, 320, 240, 30, null, "libx264", "yuv444p");
        RenderValidator.Result r = v.validate(f, new RenderValidator.Expected(2.0, 320, 240, 30, true));
        String all = String.join(" | ", r.problems());
        assertTrue(all.contains("There is no sound"), all);
        assertTrue(all.contains("isn't 4:2:0 (yuv420p); it's yuv444p"), all);
        RenderValidator.Result noAudioNeeded = v.validate(make("ok.mp4", 2, 320, 240, 30, null, "libx264", "yuv420p"),
                new RenderValidator.Expected(2.0, 320, 240, 30, false));
        assertTrue(noAudioNeeded.ok(), noAudioNeeded.problems().toString());
    }

    @Test
    void aFileThatIsntAVideoFails() throws Exception {
        Path f = Files.createTempFile("leenax-", ".mp4");
        Files.writeString(f, "not a video");
        RenderValidator.Result r = v.validate(f, new RenderValidator.Expected(2.0, 320, 240, 30, false));
        assertFalse(r.ok());
        assertTrue(r.problems().get(0).startsWith("ffprobe couldn't read the file"), r.problems().toString());
    }

    @Test
    void meanVolumeIsReadFromFfmpeg() {
        assertEquals(-23.4, RenderValidator.meanVolume("[Parsed_volumedetect_0 @ 0x1] mean_volume: -23.4 dB\n max_volume: -3.0 dB"));
        assertEquals(Double.NEGATIVE_INFINITY, RenderValidator.meanVolume("mean_volume: -inf dB"));
        assertEquals(29.97, RenderValidator.rate("30000/1001"), 0.01);
    }
}
