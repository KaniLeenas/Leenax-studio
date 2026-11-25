package com.leenax.studio.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KlingAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderCredentials key = new ProviderCredentials("kling-api-key-5555");
    private MockServer server;
    private KlingAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockServer();
        adapter = new KlingAdapter(new ProviderHttp(mapper), server.url());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void textToVideoUsesModelInPath() throws Exception {
        server.on("POST /text-to-video/kling-4.0", r -> MockServer.Reply.json(200,
                "{\"code\":0,\"message\":\"SUCCEED\",\"request_id\":\"r\",\"data\":{\"id\":\"k-1\",\"status\":\"submitted\"}}"));
        VideoResult res = adapter.submit(new VideoRequest("job-77", VideoRequest.Mode.TEXT_TO_VIDEO, "kling-4.0",
                "Kolam drawing time-lapse", null, 10, "9:16", "1080p", null, null), key);
        assertEquals("k-1", res.providerJobId());
        var req = server.last("POST", "/text-to-video/kling-4.0");
        assertEquals("Bearer kling-api-key-5555", req.header("Authorization"));
        JsonNode body = mapper.readTree(req.body());
        assertEquals(10, body.at("/settings/duration").asInt());
        assertEquals("9:16", body.at("/settings/aspect_ratio").asText());
        assertEquals("1080p", body.at("/settings/resolution").asText());
        assertEquals("job-77", body.at("/options/external_task_id").asText());
    }

    @Test
    void imageToVideoSendsRawBase64FirstFrameWithoutAspectRatio() throws Exception {
        server.on("POST /image-to-video/kling-4.0-flash", r -> MockServer.Reply.json(200,
                "{\"code\":0,\"data\":{\"id\":\"k-2\",\"status\":\"submitted\"}}"));
        adapter.submit(new VideoRequest("j", VideoRequest.Mode.IMAGE_TO_VIDEO, "kling-4.0-flash", "wave", null, 5,
                "16:9", "720p", null, new VideoRequest.InputImage(new byte[]{1, 2, 3}, "image/png", null)), key);
        JsonNode body = mapper.readTree(server.last("POST", "/image-to-video/kling-4.0-flash").body());
        assertEquals("first_frame", body.at("/contents/0/type").asText());
        assertEquals("AQID", body.at("/contents/0/url").asText());
        assertTrue(body.at("/settings/aspect_ratio").isMissingNode());
    }

    @Test
    void businessErrorCodeOnHttp200IsAnError() {
        server.on("POST /text-to-video/kling-4.0", r -> MockServer.Reply.json(200,
                "{\"code\":1102,\"message\":\"Account balance not enough\",\"data\":null}"));
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.submit(new VideoRequest("j",
                VideoRequest.Mode.TEXT_TO_VIDEO, "kling-4.0", "x", null, 5, null, null, null, null), key));
        assertEquals(ProviderErrorKind.INSUFFICIENT_CREDITS, e.kind());
    }

    @Test
    void pollsTasksAndReadsOutputs() {
        server.on("GET /tasks", r -> {
            if (r.pathAndQuery().contains("task_ids=k-run")) {
                return MockServer.Reply.json(200, "{\"code\":0,\"data\":[{\"id\":\"k-run\",\"status\":\"processing\"}]}");
            }
            if (r.pathAndQuery().contains("task_ids=k-bad")) {
                return MockServer.Reply.json(200, "{\"code\":0,\"data\":[{\"id\":\"k-bad\",\"status\":\"failed\",\"message\":\"Content violates policy\"}]}");
            }
            return MockServer.Reply.json(200, """
                    {"code":0,"data":[{"id":"k-ok","status":"succeeded",
                      "outputs":[{"type":"video","id":"o1","url":"https://cdn.kling/x.mp4","duration":"10"}],
                      "usage":{"output_duration":10}}]}""");
        });
        assertEquals(VideoResult.State.RUNNING, adapter.poll("k-run", key).state());
        VideoResult ok = adapter.poll("k-ok", key);
        assertEquals("https://cdn.kling/x.mp4", ok.output().url());
        assertEquals(Double.valueOf(10.0), ok.outputSeconds());
        assertEquals(ProviderErrorKind.CONTENT_POLICY_REJECTED, adapter.poll("k-bad", key).errorKind());
    }

    @Test
    void balancesKeepOnlyActivePacksWithExpiry() {
        server.on("GET /account/costs", r -> MockServer.Reply.json(200, """
                {"code":0,"data":{"code":0,"msg":"ok","resource_pack_subscribe_infos":[
                  {"resource_pack_name":"Video trial pack","resource_pack_id":"p1","remaining_quantity":66.5,"total_quantity":100,
                   "invalid_time":1798761600000,"status":"online"},
                  {"resource_pack_name":"Old pack","remaining_quantity":0,"status":"expired"}]}}"""));
        var balances = adapter.balances(key);
        assertEquals(1, balances.size());
        assertEquals(new BigDecimal("66.5"), balances.get(0).amount());
        assertTrue(balances.get(0).free());
        assertEquals(Instant.ofEpochMilli(1798761600000L), balances.get(0).expiresAt());
        assertTrue(server.last("GET", "/account/costs").pathAndQuery().contains("start_time="));
    }
}
