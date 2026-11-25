package com.leenax.studio.audio;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real FFmpeg recipes when ffmpeg is installed (skipped otherwise, e.g. in the Maven build image). */
class AudioRecipesTest {

    private static boolean hasFfmpeg() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static double duration(Path p) throws Exception {
        Process pr = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p.toString())
                .redirectErrorStream(true).start();
        String out = new String(pr.getInputStream().readAllBytes()).strip();
        pr.waitFor();
        return Double.parseDouble(out);
    }

    private static Path tone(Ffmpeg ff, String name, String expr, double seconds) throws Exception {
        Path p = Files.createTempFile(name, ".wav");
        ff.run(List.of("-f", "lavfi", "-i", expr + ":d=" + seconds, p.toString()));
        return p;
    }

    @Test
    void atempoChainStaysInRange() {
        assertEquals(List.of("atempo=2.0", "atempo=1.5000"), AudioRecipes.atempoChain(3.0));
        assertEquals(List.of("atempo=0.5", "atempo=0.8000"), AudioRecipes.atempoChain(0.4));
        assertEquals(List.of(), AudioRecipes.atempoChain(1.0));
    }

    @Test
    void processRejectsEmptySelection() {
        assertThrows(IllegalArgumentException.class, () -> AudioRecipes.process(Path.of("a"), Path.of("b"), List.of()));
    }

    @Test
    void realFfmpegCleanupSpeedConcatAndMix() throws Exception {
        Assumptions.assumeTrue(hasFfmpeg());
        Ffmpeg ff = new Ffmpeg("ffmpeg", 60);
        List<Path> temps = new ArrayList<>();
        try {
            // "voice": 1 s tone, 2 s silence, 1 s tone
            Path voice = Files.createTempFile("voice", ".wav");
            temps.add(voice);
            ff.run(List.of("-f", "lavfi", "-i", "sine=f=220:d=1", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono:d=2",
                    "-f", "lavfi", "-i", "sine=f=330:d=1", "-filter_complex", "[0:a][1:a][2:a]concat=n=3:v=0:a=1", voice.toString()));
            assertEquals(4.0, duration(voice), 0.1);

            Path cleaned = Files.createTempFile("clean", ".mp3");
            temps.add(cleaned);
            ff.run(AudioRecipes.process(voice, cleaned, List.of(AudioRecipes.Operation.values())));
            double d = duration(cleaned);
            assertTrue(d < 3.2 && d > 1.8, "silence should be trimmed, got " + d);

            Path faster = Files.createTempFile("fast", ".mp3");
            temps.add(faster);
            ff.run(AudioRecipes.speedAndPitch(voice, faster, 2.0, 3));
            assertEquals(2.0, duration(faster), 0.15);

            Path a = tone(ff, "a", "sine=f=440", 1.0);
            Path b = tone(ff, "b", "sine=f=550", 1.5);
            temps.add(a);
            temps.add(b);
            Path joined = Files.createTempFile("joined", ".mp3");
            temps.add(joined);
            ff.run(AudioRecipes.concat(List.of(a, b), joined, 0.3));
            assertEquals(2.8, duration(joined), 0.15);

            Path music = tone(ff, "music", "sine=f=110", 1.0); // shorter than the voice: must loop
            temps.add(music);
            Path mixed = Files.createTempFile("mixed", ".mp3");
            temps.add(mixed);
            ff.run(AudioRecipes.mixWithMusic(voice, music, mixed, -18, true, 1.0, 4.0));
            assertEquals(4.0, duration(mixed), 0.15);

            Path stt = Files.createTempFile("stt", ".mp3");
            temps.add(stt);
            ff.run(AudioRecipes.forTranscription(voice, stt));
            assertTrue(Files.size(stt) < 40_000);
        } finally {
            for (Path p : temps) Files.deleteIfExists(p);
        }
    }

    @Test
    void ffmpegErrorsAreReadable() {
        Assumptions.assumeTrue(hasFfmpeg());
        Ffmpeg ff = new Ffmpeg("ffmpeg", 30);
        Ffmpeg.FfmpegException e = assertThrows(Ffmpeg.FfmpegException.class,
                () -> ff.run(List.of("-i", "/no/such/file.wav", "/tmp/out.mp3")));
        assertTrue(e.getMessage().startsWith("FFmpeg failed"));
    }
}
