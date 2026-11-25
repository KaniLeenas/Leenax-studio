package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderCredentials key = new ProviderCredentials("AIza-test-9876");
    private MockServer server;
    private GeminiAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockServer();
        adapter = new GeminiAdapter(new ProviderHttp(mapper), server.url());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void videoWithUriDeliveryThenFilePollingThenAuthenticatedDownload() throws Exception {
        server.on("POST /v1beta/interactions", r -> MockServer.Reply.json(200, """
                {"id":"int-1","status":"completed","model":"gemini-omni-1.1-flash","object":"interaction",
                 "steps":[{"type":"model_output","content":[{"type":"video","mime_type":"video/mp4",
                   "uri":"https://generativelanguage.googleapis.com/v1beta/files/abc123"}]}]}"""));
        server.on("GET /v1beta/files/abc123", r -> MockServer.Reply.json(200, "{\"name\":\"files/abc123\",\"state\":\"ACTIVE\"}"));
        server.on("GET /v1beta/files/abc123:download", r -> MockServer.Reply.bytes("MP4DATA"));

        VideoResult submitted = adapter.submit(new VideoRequest("j", VideoRequest.Mode.TEXT_TO_VIDEO,
                "gemini-omni-1.1-flash", "Chennai beach at sunset", "crowds", 8, "9:16", "1080p", false, null), key);
        assertEquals(VideoResult.State.RUNNING, submitted.state());
        assertEquals("file:abc123", submitted.providerJobId());

        JsonNode body = mapper.readTree(server.last("POST", "/v1beta/interactions").body());
        assertEquals("gemini-omni-1.1-flash", body.get("model").asText());
        assertEquals("9:16", body.at("/response_format/aspect_ratio").asText());
        assertEquals("1080p", body.at("/response_format/resolution").asText());
        assertEquals("uri", body.at("/response_format/delivery").asText());
        assertEquals("text_to_video", body.at("/generation_config/video_config/task").asText());
        String prompt = body.at("/input/0/text").asText();
        assertTrue(prompt.contains("about 8 seconds") && prompt.contains("Avoid: crowds") && prompt.contains("No audio"));
        assertEquals("AIza-test-9876", server.last("POST", "/v1beta/interactions").header("x-goog-api-key"));

        VideoResult done = adapter.poll(submitted.providerJobId(), key);
        assertEquals(VideoResult.State.SUCCEEDED, done.state());
        assertTrue(done.output().needsAuth());

        Path out = Files.createTempFile("gm", ".mp4");
        adapter.download(done.output(), key, out);
        assertEquals("MP4DATA", Files.readString(out));
        assertEquals("AIza-test-9876", server.last("GET", "/v1beta/files/abc123:download").header("x-goog-api-key"));
        Files.delete(out);
    }

    @Test
    void inlineVideoAndImageInput() throws Exception {
        String b64 = java.util.Base64.getEncoder().encodeToString("INLINE".getBytes());
        server.on("POST /v1beta/interactions", r -> MockServer.Reply.json(200,
                "{\"id\":\"int-2\",\"status\":\"completed\",\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"video\",\"mime_type\":\"video/mp4\",\"data\":\"" + b64 + "\"}]}]}"));
        VideoResult res = adapter.submit(new VideoRequest("j", VideoRequest.Mode.IMAGE_TO_VIDEO, "gemini-omni-1.1-flash",
                "animate", null, null, null, null, null, new VideoRequest.InputImage(new byte[]{9}, "image/jpeg", null)), key);
        assertEquals(VideoResult.State.SUCCEEDED, res.state());
        assertEquals("INLINE", new String(res.output().inlineBytes()));
        JsonNode body = mapper.readTree(server.last("POST", "/v1beta/interactions").body());
        assertEquals("image", body.at("/input/1/type").asText());
        assertEquals("image/jpeg", body.at("/input/1/mime_type").asText());
        assertEquals("image_to_video", body.at("/generation_config/video_config/task").asText());
    }

    @Test
    void fileIdParsing() {
        assertEquals("abc", GeminiAdapter.fileId("files/abc"));
        assertEquals("abc", GeminiAdapter.fileId("https://x/v1beta/files/abc:download?alt=media"));
        assertNull(GeminiAdapter.fileId("https://x/other/abc"));
        assertNull(GeminiAdapter.fileId("files/../../etc"));
    }

    @Test
    void listsTextModelsAndCompletes() throws Exception {
        server.on("GET /v1beta/models", r -> MockServer.Reply.json(200, """
                {"models":[{"name":"models/gemini-x-pro","supportedGenerationMethods":["generateContent","countTokens"]},
                           {"name":"models/text-embedding-9","supportedGenerationMethods":["embedContent"]}]}"""));
        assertEquals(List.of("gemini-x-pro"), adapter.listModels(key));

        server.on("POST /v1beta/models/gemini-x-pro:generateContent", r -> MockServer.Reply.json(200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Better prompt \"}]}}]}"));
        assertEquals("Better prompt", adapter.complete("gemini-x-pro", "sys", "user", key));
        JsonNode body = mapper.readTree(server.last("POST", "/v1beta/models/gemini-x-pro:generateContent").body());
        assertEquals("sys", body.at("/systemInstruction/parts/0/text").asText());
    }
}
