package com.leenax.studio.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptTextTest {

    @Test
    void pronunciationRulesRespectWordBoundariesForLatinAndLiteralForTamil() {
        var rules = List.of(new ScriptText.Rule("Leenax", "Lee-nax"), new ScriptText.Rule("AI", "A.I."),
                new ScriptText.Rule("தோசை", "தோ-சை"));
        String out = ScriptText.applyPronunciation("Leenax uses ai, not FAIR. சுவையான தோசை!", rules);
        assertEquals("Lee-nax uses A.I., not FAIR. சுவையான தோ-சை!", out);
    }

    @Test
    void pausesUseProviderSyntax() {
        String out = ScriptText.renderPauses("Hello [pause] friends [pause 2s] welcome [PAUSE 1.5 s] back",
                s -> "<" + s + ">");
        assertEquals("Hello <0.5> friends <2.0> welcome <1.5> back", out);
        assertEquals("Hello friends", ScriptText.stripPauses("Hello [pause 3s] friends"));
    }

    @Test
    void chunksRespectLimitAndPreferParagraphsAndSentences() {
        String para1 = "First sentence here. Second sentence follows. Third one ends it.";
        String para2 = "Another paragraph starts. It also has sentences.";
        List<String> chunks = ScriptText.chunk(para1 + "\n\n" + para2, 70);
        for (String c : chunks) assertTrue(c.length() <= 70, c);
        String norm = String.join(" ", chunks).replaceAll("\\s+", " ");
        assertEquals((para1 + " " + para2).replaceAll("\\s+", " "), norm);
        assertTrue(chunks.size() >= 2);
    }

    @Test
    void veryLongSentencesFallBackToWords() {
        String s = "word ".repeat(100).strip();
        List<String> chunks = ScriptText.chunk(s, 50);
        for (String c : chunks) assertTrue(c.length() <= 50);
        assertEquals(100, String.join(" ", chunks).split(" ").length);
    }

    @Test
    void parsesDialogueWithContinuationLines() {
        var lines = ScriptText.parseDialogue("Kani: வணக்கம்!\nGuest: Thanks for having me.\nIt's great to be here.\n\nKani： Let's start.");
        assertEquals(3, lines.size());
        assertEquals("Guest", lines.get(1).speaker());
        assertEquals("Thanks for having me. It's great to be here.", lines.get(1).text());
        assertEquals("Kani", lines.get(2).speaker());
    }
}
