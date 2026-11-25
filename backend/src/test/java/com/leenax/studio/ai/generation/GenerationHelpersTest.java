package com.leenax.studio.ai.generation;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationHelpersTest {

    private static GenerationJob job(String prompt, int variation) {
        GenerationJob j = new GenerationJob(UUID.randomUUID(), variation);
        j.setPrompt(prompt);
        return j;
    }

    @Test
    void outputNamesComeFromThePrompt() {
        assertEquals("madurai-street-food-at-night.mp4", GenerationProcessor.outputName(job("Madurai street food, at night!", 0)));
        assertEquals("madurai-street-food-at-night-v3.mp4", GenerationProcessor.outputName(job("Madurai street food, at night!", 2)));
        assertEquals("மதுரை-தெரு.mp4", GenerationProcessor.outputName(job("மதுரை தெரு", 0)));
        assertEquals("ai-video.mp4", GenerationProcessor.outputName(job("!!!", 0)));
    }

    @Test
    void backoffDoublesAndCaps() {
        assertEquals(15, GenerationProcessor.backoffSeconds(0));
        assertEquals(30, GenerationProcessor.backoffSeconds(1));
        assertEquals(240, GenerationProcessor.backoffSeconds(4));
        assertEquals(300, GenerationProcessor.backoffSeconds(9));
    }

    @Test
    void enhancerStripsLabelsAndQuotes() {
        assertEquals("A slow dolly shot", PromptEnhancer.clean("Prompt: \"A slow dolly shot\""));
        assertEquals("Plain", PromptEnhancer.clean("  Plain  "));
    }
}
