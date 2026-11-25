package com.leenax.studio.ai.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.provider.VideoRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelOptionsTest {

    @SuppressWarnings("unchecked")
    private static ModelOptions parse(String json) throws Exception {
        return new ModelOptions(new ObjectMapper().readValue(json, Map.class));
    }

    @Test
    void readsTheSeededRunwayShape() throws Exception {
        ModelOptions o = parse("""
                {"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durations":{"values":[4,6,8]},"defaultDuration":8,"audio":true,
                 "aspectRatios":{"ALL":[{"value":"1920:1080","label":"16:9"},{"value":"1080:1920","label":"9:16"}]}}""");
        assertTrue(o.supports(VideoRequest.Mode.IMAGE_TO_VIDEO));
        assertTrue(o.supportsAudio());
        assertEquals(Integer.valueOf(8), o.defaultDuration());
        assertNull(o.validateDuration(6));
        assertTrue(o.validateDuration(5).contains("[4, 6, 8]"));
        assertEquals(List.of("1920:1080", "1080:1920"), o.aspectRatios(VideoRequest.Mode.TEXT_TO_VIDEO));
    }

    @Test
    void perModeRatiosAndRanges() throws Exception {
        ModelOptions o = parse("""
                {"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durations":{"min":3,"max":30},
                 "aspectRatios":{"TEXT_TO_VIDEO":[{"value":"16:9"}]},"resolutions":[{"value":"1080p"},{"value":"720p"}]}""");
        assertEquals(List.of("16:9"), o.aspectRatios(VideoRequest.Mode.TEXT_TO_VIDEO));
        assertEquals(List.of(), o.aspectRatios(VideoRequest.Mode.IMAGE_TO_VIDEO));
        assertEquals(List.of("1080p", "720p"), o.resolutions());
        assertNull(o.validateDuration(30));
        assertTrue(o.validateDuration(31).contains("between 3 and 30"));
        assertNull(o.validateDuration(null));
    }

    @Test
    void imageOnlyModel() throws Exception {
        ModelOptions o = parse("{\"modes\":[\"IMAGE_TO_VIDEO\"]}");
        assertTrue(!o.supports(VideoRequest.Mode.TEXT_TO_VIDEO));
        assertNull(o.validateDuration(99));
    }
}
