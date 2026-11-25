package com.leenax.studio.ai.provider;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal multipart/form-data body builder for the JDK HttpClient. */
public final class Multipart {

    private final String boundary = "----leenax" + UUID.randomUUID().toString().replace("-", "");
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public Multipart field(String name, String value) {
        if (value == null) return this;
        write("--" + boundary + "\r\n");
        write("Content-Disposition: form-data; name=\"" + escape(name) + "\"\r\n\r\n");
        write(value);
        write("\r\n");
        return this;
    }

    public Multipart file(String name, String filename, String contentType, byte[] data) {
        write("--" + boundary + "\r\n");
        write("Content-Disposition: form-data; name=\"" + escape(name) + "\"; filename=\"" + escape(filename) + "\"\r\n");
        write("Content-Type: " + contentType + "\r\n\r\n");
        out.writeBytes(data);
        write("\r\n");
        return this;
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public HttpRequest.BodyPublisher publisher() {
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        copy.writeBytes(out.toByteArray());
        copy.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArray(copy.toByteArray());
    }

    private void write(String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        return s.replace("\"", "%22").replace("\r", "").replace("\n", "");
    }
}
