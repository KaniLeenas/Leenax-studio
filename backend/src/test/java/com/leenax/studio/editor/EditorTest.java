package com.leenax.studio.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String M1 = "3f6a1c2e-1b2a-4c3d-9e8f-0a1b2c3d4e5f";

    private static JsonNode doc(String tracks) throws Exception {
        return JSON.readTree("""
                {"version":1,"width":1920,"height":1080,"fps":30,"background":"#000000","tracks":[%s]}""".formatted(tracks));
    }

    private static String mediaClip(String id, String kind, double start, double duration) {
        return """
                {"id":"%s","type":"media","mediaId":"%s","kind":"%s","start":%s,"duration":%s,"trimStart":0,"speed":1,"volume":1}"""
                .formatted(id, M1, kind, start, duration);
    }

    @Test
    void acceptsAValidTimelineAndCollectsMedia() throws Exception {
        var s = TimelineDocument.check(doc("""
                {"id":"t1","kind":"text","volume":1,"clips":[{"id":"c1","type":"text","start":0,"duration":2,"text":"வணக்கம்"}]},
                {"id":"t2","kind":"video","volume":1,"clips":[%s,%s]},
                {"id":"t3","kind":"audio","volume":1,"clips":[%s]}""".formatted(
                mediaClip("c2", "VIDEO", 0, 5), mediaClip("c3", "IMAGE", 5, 2.5), mediaClip("c4", "AUDIO", 0, 30))));
        assertEquals(30.0, s.durationSeconds());
        assertEquals(4, s.clips());
        assertEquals(java.util.Set.of(UUID.fromString(M1)), s.mediaIds());
    }

    @Test
    void refusesWrongTracksSizesAndIds() throws Exception {
        var e1 = assertThrows(TimelineDocument.InvalidTimelineException.class,
                () -> TimelineDocument.check(doc("{\"id\":\"t\",\"kind\":\"audio\",\"clips\":[" + mediaClip("c", "VIDEO", 0, 1) + "]}")));
        assertTrue(e1.getMessage().contains("video clip is on a audio track"));
        assertThrows(TimelineDocument.InvalidTimelineException.class, () -> TimelineDocument.check(JSON.readTree(
                "{\"version\":1,\"width\":1921,\"height\":1080,\"fps\":30,\"background\":\"#000000\",\"tracks\":[]}")));
        assertThrows(TimelineDocument.InvalidTimelineException.class, () -> TimelineDocument.check(JSON.readTree(
                "{\"version\":1,\"width\":1920,\"height\":1080,\"fps\":29,\"background\":\"#000000\",\"tracks\":[]}")));
        assertThrows(TimelineDocument.InvalidTimelineException.class, () -> TimelineDocument.check(doc(
                "{\"id\":\"t\",\"kind\":\"video\",\"clips\":[{\"id\":\"c\",\"type\":\"media\",\"kind\":\"VIDEO\",\"mediaId\":\"../etc\",\"start\":0,\"duration\":1}]}")));
        assertThrows(TimelineDocument.InvalidTimelineException.class, () -> TimelineDocument.check(doc(
                "{\"id\":\"t\",\"kind\":\"video\",\"clips\":[" + mediaClip("c", "VIDEO", 0, 1) + "," + mediaClip("c", "VIDEO", 2, 1) + "]}")));
        assertThrows(TimelineDocument.InvalidTimelineException.class, () -> TimelineDocument.check(doc(
                "{\"id\":\"t\",\"kind\":\"video\",\"clips\":[" + mediaClip("c", "VIDEO", 0, 4 * 3600) + "]}")));
    }

    @Test
    void renderSettingsKeepAspectAndEvenSizes() {
        var hd = new RenderSettings(720, RenderSettings.Quality.STANDARD);
        assertEquals("1280x720", size(hd.outputSize(1920, 1080)));
        assertEquals("720x1280", size(hd.outputSize(1080, 1920)));
        assertEquals("720x900", size(hd.outputSize(1080, 1350)));
        var uhd = new RenderSettings(2160, RenderSettings.Quality.BEST);
        assertEquals("3840x2160", size(uhd.outputSize(1920, 1080)));
        assertEquals(Map.of("crf", 18, "x264Preset", "slow"), uhd.rendererParams());
        assertEquals("veryfast", new RenderSettings(1080, RenderSettings.Quality.HIGH).rendererParams().get("x264Preset"));
        assertEquals(RenderSettings.Quality.STANDARD, RenderSettings.fromMap(Map.of("resolution", 1080, "quality", "nonsense")).quality());
    }

    @Test
    void backoffGrowsAndCaps() {
        assertEquals(15, RenderProcessor.backoff(1).toSeconds());
        assertEquals(60, RenderProcessor.backoff(3).toSeconds());
        assertEquals(300, RenderProcessor.backoff(9).toSeconds());
    }

    @Test
    void rendererClientSpeaksTheRenderServiceApi() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> posted = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        server.createContext("/", ex -> {
            token.set(ex.getRequestHeaders().getFirst("X-Renderer-Token"));
            String path = ex.getRequestURI().getPath();
            byte[] out;
            int status;
            if (ex.getRequestMethod().equals("POST") && path.equals("/renders")) {
                posted.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                status = 202;
                out = "{\"status\":\"queued\"}".getBytes();
            } else if (path.equals("/renders/job-1")) {
                status = 200;
                out = "{\"status\":\"rendering\",\"progress\":0.42}".getBytes();
            } else if (path.equals("/renders/job-1/file")) {
                status = 200;
                out = "MP4".getBytes();
            } else {
                status = 404;
                out = "{\"error\":\"Unknown render\"}".getBytes();
            }
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            var client = new RendererClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", "s3cret", JSON);
            client.submit("job-1", Map.of("timeline", Map.of("width", 1920)), Map.of("crf", 23));
            JsonNode body = JSON.readTree(posted.get());
            assertEquals("job-1", body.get("id").asText());
            assertEquals(1920, body.at("/props/timeline/width").asInt());
            assertEquals(23, body.get("crf").asInt());
            assertEquals("s3cret", token.get());

            var st = client.status("job-1");
            assertEquals("rendering", st.status());
            assertEquals(0.42, st.progress(), 1e-9);
            assertThrows(RendererClient.UnknownRenderException.class, () -> client.status("job-2"));

            Path file = Files.createTempFile("render", ".mp4");
            client.download("job-1", file);
            assertEquals("MP4", Files.readString(file));
            Files.delete(file);
        } finally {
            server.stop(0);
        }
        var offline = new RendererClient("http://127.0.0.1:1", "", JSON);
        assertThrows(RendererClient.UnavailableException.class, () -> offline.status("x"));
    }

    private static String size(int[] s) {
        return s[0] + "x" + s[1];
    }
}
