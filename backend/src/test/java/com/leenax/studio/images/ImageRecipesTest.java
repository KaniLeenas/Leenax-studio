package com.leenax.studio.images;

import com.leenax.studio.audio.Ffmpeg;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageRecipesTest {

    private static boolean hasFfmpeg() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String size(Path p) throws Exception {
        Process pr = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=width,height,pix_fmt", "-of", "csv=p=0", p.toString()).redirectErrorStream(true).start();
        String out = new String(pr.getInputStream().readAllBytes()).strip();
        pr.waitFor();
        return out;
    }

    @Test
    void resizesToExactFrameBothWays() throws Exception {
        Assumptions.assumeTrue(hasFfmpeg());
        Ffmpeg ff = new Ffmpeg("ffmpeg", 30);
        Path src = Files.createTempFile("src", ".png");
        Path cover = Files.createTempFile("cover", ".jpg");
        Path contain = Files.createTempFile("contain", ".png");
        try {
            ff.run(List.of("-f", "lavfi", "-i", "testsrc=size=800x600", "-frames:v", "1", src.toString()));
            ff.run(ImageRecipes.resize(src, cover, 1280, 720, ImageRecipes.Fit.COVER, null));
            assertEquals("1280,720", size(cover).replaceAll(",yuvj444p$", ""));
            ff.run(ImageRecipes.resize(src, contain, 1080, 1920, ImageRecipes.Fit.CONTAIN, null));
            assertEquals("1080,1920,rgba", size(contain));
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(cover);
            Files.deleteIfExists(contain);
        }
    }
}
