package com.leenax.studio.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks the document service how a deck's text fits its boxes (POST /slides/fit). It measures with
 * fonts that have the same widths as the ones PowerPoint uses, so the answer holds for the PPTX.
 * When it can't be asked, the result says so ({checked: false}) and the deck can't be approved.
 */
@Component
public class SlideFitter {

    static final ObjectMapper JSON = new ObjectMapper();
    private final String docservice;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public SlideFitter(@Value("${leenax.docservice.url:}") String docservice) {
        this.docservice = docservice == null ? "" : docservice.replaceAll("/+$", "");
    }

    public JsonNode fit(JsonNode deck) {
        if (docservice.isBlank()) return unchecked("The document service isn't set up on this server");
        try {
            ObjectNode body = JSON.createObjectNode();
            body.set("deck", deck);
            HttpRequest req = HttpRequest.newBuilder(URI.create(docservice + "/slides/fit"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) return unchecked("The document service answered " + res.statusCode());
            JsonNode out = JSON.readTree(res.body());
            if (!out.has("ok") || !out.path("slides").isArray()) return unchecked("The document service's answer wasn't understood");
            ((ObjectNode) out).put("checked", true);
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unchecked("Interrupted");
        } catch (Exception e) {
            return unchecked("The document service isn't reachable");
        }
    }

    public static JsonNode unchecked(String why) {
        ObjectNode n = JSON.createObjectNode();
        n.put("checked", false);
        n.put("ok", false);
        n.put("error", why);
        return n;
    }
}
