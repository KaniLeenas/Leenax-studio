package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunwayAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderCredentials key = new ProviderCredentials("key_runway_1234");
    private MockServer server;
    private RunwayAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockServer();
        adapter = new RunwayAdapter(new ProviderHttp(mapper), server.url());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void sendsAuthAndVersionHeadersAndReadsBalance() {
        server.on("GET /v1/organization", r -> MockServer.Reply.json(200,
                "{\"creditBalance\":1500,\"tier\":{\"maxMonthlyCreditSpend\":10000,\"models\":{}},\"usage\":{\"models\":{}}}"));
        var balances = adapter.balances(key);
        assertEquals(1, balances.size());
        assertEquals(new BigDecimal("1500"), balances.get(0).amount());
        assertEquals("CREDITS", balances.get(0).unit());
        var req = server.last("GET", "/v1/organization");
        assertEquals("Bearer key_runway_1234", req.header("Authorization"));
        assertEquals("2024-11-06", req.header("X-Runway-Version"));
    }

    @Test
    void textToVideoBodyAndEstimatedCost() throws Exception {
        server.on("POST /v1/text_to_video", r -> MockServer.Reply.json(200,
                "{\"id\":\"task-1\",\"estimatedCost\":{\"credits\":60}}"));
        VideoResult res = adapter.submit(new VideoRequest("job-1", VideoRequest.Mode.TEXT_TO_VIDEO, "gen4.5",
                "A temple at dawn", "people", 5, "1280:720", null, null, null), key);
        assertEquals("task-1", res.providerJobId());
        assertEquals(VideoResult.State.RUNNING, res.state());
        assertEquals(new BigDecimal("60"), res.cost());

        JsonNode body = mapper.readTree(server.last("POST", "/v1/text_to_video").body());
        assertEquals("gen4.5", body.get("model").asText());
        assertEquals("A temple at dawn\nAvoid: people", body.get("promptText").asText());
        assertEquals("1280:720", body.get("ratio").asText());
        assertEquals(5, body.get("duration").asInt());
        assertTrue(body.get("audio") == null, "audio is only sent when set");
    }

    @Test
    void imageToVideoSendsDataUriAsFirstFrame() throws Exception {
        server.on("POST /v1/image_to_video", r -> MockServer.Reply.json(200, "{\"id\":\"task-2\",\"estimatedCost\":{\"credits\":25}}"));
        var image = new VideoRequest.InputImage(new byte[]{1, 2, 3}, "image/png", null);
        adapter.submit(new VideoRequest("j", VideoRequest.Mode.IMAGE_TO_VIDEO, "gen4_turbo", "zoom in", null, 5,
                "1280:720", null, null, image), key);
        JsonNode body = mapper.readTree(server.last("POST", "/v1/image_to_video").body());
        assertEquals("data:image/png;base64,AQID", body.at("/promptImage/0/uri").asText());
        assertEquals("first", body.at("/promptImage/0/position").asText());
    }

    @Test
    void pollMapsStatesCostAndSafetyFailures() {
        server.on("GET /v1/tasks/a", r -> MockServer.Reply.json(200, "{\"id\":\"a\",\"status\":\"RUNNING\",\"progress\":0.42}"));
        server.on("GET /v1/tasks/b", r -> MockServer.Reply.json(200,
                "{\"id\":\"b\",\"status\":\"SUCCEEDED\",\"output\":[\"https://cdn.example/v.mp4\"],\"cost\":{\"credits\":55}}"));
        server.on("GET /v1/tasks/c", r -> MockServer.Reply.json(200,
                "{\"id\":\"c\",\"status\":\"FAILED\",\"failure\":\"Prompt flagged\",\"failureCode\":\"SAFETY.INPUT.TEXT\"}"));
        server.on("GET /v1/tasks/d", r -> MockServer.Reply.json(200, "{\"id\":\"d\",\"status\":\"THROTTLED\"}"));

        VideoResult running = adapter.poll("a", key);
        assertEquals(VideoResult.State.RUNNING, running.state());
        assertEquals(Double.valueOf(0.42), running.progress());

        VideoResult done = adapter.poll("b", key);
        assertEquals(VideoResult.State.SUCCEEDED, done.state());
        assertEquals("https://cdn.example/v.mp4", done.output().url());
        assertEquals(new BigDecimal("55"), done.cost());

        VideoResult failed = adapter.poll("c", key);
        assertEquals(ProviderErrorKind.CONTENT_POLICY_REJECTED, failed.errorKind());
        assertTrue(failed.errorMessage().contains("Prompt flagged"));

        assertEquals(VideoResult.State.RUNNING, adapter.poll("d", key).state());
    }

    @Test
    void mapsHttpErrors() {
        server.on("GET /v1/organization", r -> MockServer.Reply.json(401, "{\"error\":\"Invalid API key\"}"));
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.verify(key));
        assertEquals(ProviderErrorKind.INVALID_CREDENTIALS, e.kind());
        assertTrue(!e.getMessage().contains("key_runway_1234"), "never echoes the key");

        server.on("POST /v1/text_to_video", r -> MockServer.Reply.json(429, "{\"error\":\"Too many requests\"}"));
        ProviderException rate = assertThrows(ProviderException.class, () -> adapter.submit(
                new VideoRequest("j", VideoRequest.Mode.TEXT_TO_VIDEO, "gen4.5", "x", null, 5, null, null, null, null), key));
        assertTrue(rate.retryable());

        server.on("POST /v1/image_to_video", r -> MockServer.Reply.json(400, "{\"error\":\"You do not have enough credits\"}"));
        ProviderException credits = assertThrows(ProviderException.class, () -> adapter.submit(
                new VideoRequest("j", VideoRequest.Mode.IMAGE_TO_VIDEO, "gen4.5", "x", null, 5, null, null, null,
                        new VideoRequest.InputImage(new byte[]{1}, "image/png", null)), key));
        assertEquals(ProviderErrorKind.INSUFFICIENT_CREDITS, credits.kind());
    }

    @Test
    void cancelsAndDownloads() throws Exception {
        server.on("DELETE /v1/tasks/z", r -> MockServer.Reply.json(204, ""));
        assertTrue(adapter.cancel("z", key));

        server.on("GET /files/v.mp4", r -> MockServer.Reply.bytes("VIDEOBYTES"));
        Path out = Files.createTempFile("rw", ".mp4");
        adapter.download(VideoResult.Output.url(server.url() + "/files/v.mp4"), key, out);
        assertEquals("VIDEOBYTES", Files.readString(out));
        assertTrue(server.last("GET", "/files/v.mp4").header("Authorization") == null, "no key sent to the CDN");
        Files.delete(out);
    }
}
