package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frames sent to text models for footage descriptions: one request format per provider. */
class VisionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderCredentials key = new ProviderCredentials("secret-key-0000");
    private final List<MediaTypes.Sample> frames = List.of(new MediaTypes.Sample(new byte[]{1, 2, 3}, "f1.jpg", "image/jpeg"));
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
    void openAiSendsDataUrlsInChatContent() throws Exception {
        server.on("POST /v1/chat/completions", r -> MockServer.Reply.json(200, "{\"choices\":[{\"message\":{\"content\":\"A market street\"}}]}"));
        String out = new OpenAiAdapter(http, server.url()).completeWithImages("gpt-x", "sys", "describe", frames, key);
        assertEquals("A market street", out);
        JsonNode content = mapper.readTree(server.last("POST", "/v1/chat/completions").body()).at("/messages/1/content");
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("data:image/jpeg;base64,AQID", content.get(1).at("/image_url/url").asText());
    }

    @Test
    void claudeSendsBase64ImageBlocksBeforeTheText() throws Exception {
        server.on("POST /v1/messages", r -> MockServer.Reply.json(200, "{\"content\":[{\"type\":\"text\",\"text\":\"Temple\"}]}"));
        assertEquals("Temple", new AnthropicAdapter(http, server.url()).completeWithImages("claude-x", "sys", "describe", frames, key));
        JsonNode body = mapper.readTree(server.last("POST", "/v1/messages").body());
        JsonNode content = body.at("/messages/0/content");
        assertEquals("image", content.get(0).get("type").asText());
        assertEquals("AQID", content.get(0).at("/source/data").asText());
        assertEquals("image/jpeg", content.get(0).at("/source/media_type").asText());
        assertEquals("text", content.get(1).get("type").asText());
        assertEquals(8192, body.get("max_tokens").asInt());
    }

    @Test
    void geminiSendsInlineData() throws Exception {
        server.on("POST /v1beta/models/gemini-x:generateContent", r -> MockServer.Reply.json(200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Beach\"}]}}]}"));
        assertEquals("Beach", new GeminiAdapter(http, server.url()).completeWithImages("gemini-x", "sys", "describe", frames, key));
        JsonNode parts = mapper.readTree(server.last("POST", "/v1beta/models/gemini-x:generateContent").body()).at("/contents/0/parts");
        assertEquals("AQID", parts.get(0).at("/inline_data/data").asText());
        assertTrue(parts.get(1).has("text"));
    }
}
