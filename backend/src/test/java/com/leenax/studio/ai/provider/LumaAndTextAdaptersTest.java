package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumaAndTextAdaptersTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderCredentials key = new ProviderCredentials("secret-key-0000");
    private MockServer server;
    private ProviderHttp http;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockServer();
        http = new ProviderHttp(mapper);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void lumaCreatesPollsAndConvertsCentsToDollars() throws Exception {
        LumaAdapter luma = new LumaAdapter(http, server.url());
        server.on("GET /dream-machine/v1/credits", r -> MockServer.Reply.json(200, "{\"credit_balance\":1234}"));
        assertEquals(new BigDecimal("12.34"), luma.balances(key).get(0).amount());

        server.on("POST /dream-machine/v1/generations/video", r -> MockServer.Reply.json(201, "{\"id\":\"lu-1\",\"state\":\"queued\"}"));
        luma.submit(new VideoRequest("j", VideoRequest.Mode.TEXT_TO_VIDEO, "ray-2", "rain on a tin roof", null, 9,
                "16:9", "720p", null, null), key);
        JsonNode body = mapper.readTree(server.last("POST", "/dream-machine/v1/generations/video").body());
        assertEquals("9s", body.get("duration").asText());
        assertEquals("ray-2", body.get("model").asText());

        server.on("GET /dream-machine/v1/generations/lu-1", r -> MockServer.Reply.json(200,
                "{\"id\":\"lu-1\",\"state\":\"completed\",\"assets\":{\"video\":\"https://cdn.luma/v.mp4\"}}"));
        assertEquals("https://cdn.luma/v.mp4", luma.poll("lu-1", key).output().url());
    }

    @Test
    void lumaImageToVideoNeedsAPublicHttpsUrl() {
        LumaAdapter luma = new LumaAdapter(http, server.url());
        var local = new VideoRequest.InputImage(new byte[]{1}, "image/png", "http://localhost:8333/leenax-media/x.png?sig");
        ProviderException e = assertThrows(ProviderException.class, () -> luma.submit(new VideoRequest("j",
                VideoRequest.Mode.IMAGE_TO_VIDEO, "ray-2", "x", null, 5, null, null, null, local), key));
        assertEquals(ProviderErrorKind.UNSUPPORTED_CAPABILITY, e.kind());
        assertTrue(LumaAdapter.isPublic("https://media.example.com/a.png"));
        assertFalse(LumaAdapter.isPublic("https://192.168.1.4/a.png"));
        assertFalse(LumaAdapter.isPublic("https://172.20.0.3/a.png"));
    }

    @Test
    void openAiListsChatModelsAndCompletes() throws Exception {
        OpenAiAdapter openai = new OpenAiAdapter(http, server.url());
        server.on("GET /v1/models", r -> MockServer.Reply.json(200,
                "{\"data\":[{\"id\":\"model-b\"},{\"id\":\"text-embedding-big\"},{\"id\":\"model-a\"},{\"id\":\"tts-hd\"}]}"));
        assertEquals(List.of("model-a", "model-b"), openai.listModels(key));
        server.on("POST /v1/chat/completions", r -> MockServer.Reply.json(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\" Done \"}}]}"));
        assertEquals("Done", openai.complete("model-a", "sys", "hi", key));
        JsonNode body = mapper.readTree(server.last("POST", "/v1/chat/completions").body());
        assertEquals("system", body.at("/messages/0/role").asText());
        assertEquals("Bearer secret-key-0000", server.last("POST", "/v1/chat/completions").header("Authorization"));
    }

    @Test
    void anthropicSendsVersionHeaderAndJoinsTextBlocks() throws Exception {
        AnthropicAdapter claude = new AnthropicAdapter(http, server.url());
        server.on("POST /v1/messages", r -> MockServer.Reply.json(200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"Part one. \"},{\"type\":\"text\",\"text\":\"Part two.\"}]}"));
        assertEquals("Part one. Part two.", claude.complete("claude-x", "sys", "hi", key));
        var req = server.last("POST", "/v1/messages");
        assertEquals("secret-key-0000", req.header("x-api-key"));
        assertEquals("2023-06-01", req.header("anthropic-version"));
        assertEquals("sys", mapper.readTree(req.body()).get("system").asText());
    }

    @Test
    void unreachableProviderIsRetryable() {
        OpenAiAdapter openai = new OpenAiAdapter(http, "http://127.0.0.1:1");
        ProviderException e = assertThrows(ProviderException.class, () -> openai.verify(key));
        assertEquals(ProviderErrorKind.PROVIDER_UNAVAILABLE, e.kind());
        assertTrue(e.retryable());
    }
}
