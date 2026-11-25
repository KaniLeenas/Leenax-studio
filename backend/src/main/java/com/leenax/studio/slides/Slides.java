package com.leenax.studio.slides;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A presentation as data (E7). A deck belongs to one topic and notes level; each slide has a
 * type (what it's for) and a layout (where things go). Only the fields its layout shows are kept,
 * so what's exported is what people see. The fit result, from the document service, says at what
 * size each text area is drawn or how much is too long.
 */
public final class Slides {

    private Slides() {
    }

    /** The 15 slide types of the brief, in the order a lesson usually runs. */
    public static final List<String> TYPES = List.of("TITLE", "OBJECTIVES", "INTRODUCTION", "OVERVIEW", "DEFINITION", "CONCEPT", "DIAGRAM",
            "FLOWCHART", "EXAMPLE", "COMPARISON", "APPLICATIONS", "EXAM_TIPS", "SUMMARY", "REVISION", "REFERENCES");

    public static final List<String> LAYOUTS = List.of("TITLE", "BULLETS", "BULLETS_IMAGE", "DEFINITION", "TABLE", "DIAGRAM", "EXAMPLE", "IMAGE");

    /** The 7 styles; the document service holds their colours. */
    public static final Map<String, String> STYLES = styles();

    private static Map<String, String> styles() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("LECTURE", "University lecture");
        m.put("MINIMAL", "Modern minimalist");
        m.put("DARK_TECH", "Dark technology");
        m.put("INFOGRAPHIC", "Animated infographic");
        m.put("WHITEBOARD", "Whiteboard");
        m.put("CORPORATE", "Corporate education");
        m.put("ILLUSTRATED", "Illustrated presentation");
        return m;
    }

    /** The usual layout for each type. */
    public static String defaultLayout(String type) {
        return switch (type) {
            case "TITLE" -> "TITLE";
            case "DEFINITION" -> "DEFINITION";
            case "DIAGRAM", "FLOWCHART" -> "DIAGRAM";
            case "EXAMPLE" -> "EXAMPLE";
            case "COMPARISON" -> "TABLE";
            default -> "BULLETS";
        };
    }

    /** Which fields each layout shows. */
    public static List<String> fields(String layout) {
        return switch (layout) {
            case "TITLE" -> List.of("title", "subtitle");
            case "BULLETS" -> List.of("title", "bullets");
            case "BULLETS_IMAGE" -> List.of("title", "bullets", "image", "caption");
            case "DEFINITION" -> List.of("title", "term", "text");
            case "TABLE" -> List.of("title", "table");
            case "DIAGRAM" -> List.of("title", "figure", "caption");
            case "EXAMPLE" -> List.of("title", "text", "code");
            case "IMAGE" -> List.of("title", "image", "caption");
            default -> List.of("title");
        };
    }

    public static final int MAX_SLIDES = 60;
    public static final int MAX_BULLETS = 10;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Table(List<String> headers, List<List<String>> rows) {
    }

    /** A Mermaid diagram or a LaTeX equation, drawn by the browser and kept by source (as in notes). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Figure(String kind, String source) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(UUID assetId, String alt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Code(String language, String text) {
    }

    /** sources: headings of the notes the slide is drawn from. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Slide(String id, String type, String layout, String title, String subtitle, List<String> bullets, String term, String text,
                        Table table, Figure figure, Image image, String caption, Code code, String notes, List<String> sources) {
    }

    /**
     * fit: the document service's reply ({ok, method, slides: [{id, ok, boxes, problems}]}), or
     * {checked: false, error} when it couldn't be asked.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Deck(String topicId, String topicTitle, String level, UUID notesVersionId, UUID qaVersionId, UUID seoVersionId, String style,
                       String title, List<Slide> slides, JsonNode fit, String model) {
    }

    public static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }
}
