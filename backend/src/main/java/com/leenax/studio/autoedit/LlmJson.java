package com.leenax.studio.autoedit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Pulls the JSON out of a model reply (which may have code fences or a sentence around it). */
public final class LlmJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LlmJson() {
    }

    public static JsonNode extract(String reply) {
        if (reply == null) throw new IllegalArgumentException("The AI returned nothing");
        String t = reply.strip();
        int obj = t.indexOf('{');
        int arr = t.indexOf('[');
        int start = obj < 0 ? arr : arr < 0 ? obj : Math.min(obj, arr);
        if (start < 0) throw new IllegalArgumentException("The AI reply had no JSON in it");
        char open = t.charAt(start);
        int end = t.lastIndexOf(open == '{' ? '}' : ']');
        if (end <= start) throw new IllegalArgumentException("The AI reply was cut off");
        try {
            return JSON.readTree(t.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("The AI reply was not valid JSON");
        }
    }
}
