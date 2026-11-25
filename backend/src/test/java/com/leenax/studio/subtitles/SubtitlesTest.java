package com.leenax.studio.subtitles;

import com.leenax.studio.ai.provider.MediaTypes.Segment;
import com.leenax.studio.ai.provider.MediaTypes.Word;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubtitlesTest {

    private static List<Word> words(String text, double start, double step) {
        List<Word> out = new ArrayList<>();
        double t = start;
        for (String w : text.split(" ")) {
            out.add(new Word(w, t, t + step * 0.8));
            t += step;
        }
        return out;
    }

    @Test
    void breaksAtSentenceEndsAndPauses() {
        List<Word> ws = new ArrayList<>(words("Welcome back to the channel everyone.", 0.0, 0.3));
        ws.addAll(words("Today we cook Madurai jigarthanda.", 3.0, 0.3)); // > 0.6 s pause before this
        List<Cue> cues = CueBuilder.fromWords(ws, 42);
        assertEquals(2, cues.size());
        assertEquals("Welcome back to the channel everyone.", cues.get(0).text());
        assertEquals(3.0, cues.get(1).start(), 1e-9);
    }

    @Test
    void longRunsSplitIntoTwoLineCuesUnderSixSeconds() {
        String text = "this is a very long sentence without any punctuation that keeps going and going for quite a while "
                + "so the builder has to split it into several readable subtitle cues for viewers";
        List<Cue> cues = CueBuilder.fromWords(words(text, 0, 0.35), 42);
        assertTrue(cues.size() >= 3);
        for (Cue c : cues) {
            for (String line : c.text().split("\n")) assertTrue(line.length() <= 50, "line too long: " + line);
            assertTrue(c.text().split("\n").length <= 2);
            assertTrue(c.end() - c.start() <= 6.5);
        }
        for (int i = 1; i < cues.size(); i++) assertTrue(cues.get(i).start() >= cues.get(i - 1).end());
    }

    @Test
    void tamilWordsAndShortCuesGetMinimumDuration() {
        List<Cue> cues = CueBuilder.fromWords(List.of(new Word("வணக்கம்", 1.0, 1.2)), 42);
        assertEquals("வணக்கம்", cues.get(0).text());
        assertEquals(1.0, cues.get(0).end() - cues.get(0).start(), 1e-9);
    }

    @Test
    void segmentsFallbackWraps() {
        List<Cue> cues = CueBuilder.fromSegments(List.of(new Segment(0, 3, "  Hello there, this is a segment that is longer than forty two chars ")), 42);
        assertEquals(2, cues.get(0).text().split("\n").length);
    }

    @Test
    void writesSrtAndVtt() {
        List<Cue> cues = List.of(new Cue(0.5, 2.25, "Hello\nworld"), new Cue(3661.0, 3662.5, "One hour --> later"));
        String srt = SubtitleFormats.toSrt(cues);
        assertTrue(srt.startsWith("1\n00:00:00,500 --> 00:00:02,250\nHello\nworld\n\n2\n01:01:01,000 --> 01:01:02,500\n"));
        String vtt = SubtitleFormats.toVtt(cues);
        assertTrue(vtt.startsWith("WEBVTT\n\n00:00:00.500 --> 00:00:02.250\n"));
        assertTrue(vtt.contains("One hour -> later"));
    }

    @Test
    void translationKeepsTimingAndToleratesFences() {
        List<Cue> cues = List.of(new Cue(0, 1.5, "Hello"), new Cue(2, 3.5, "Good\nmorning"));
        String sent = CueTranslation.userPrompt(cues, 0);
        assertEquals("[{\"i\":0,\"t\":\"Hello\"},{\"i\":1,\"t\":\"Good\\nmorning\"}]", sent);
        var parsed = CueTranslation.parse("```json\n[{\"i\":1,\"t\":\"காலை\\nவணக்கம்\"},{\"i\":0,\"t\":\"வணக்கம்\"}]\n```");
        List<Cue> out = CueTranslation.apply(cues, parsed);
        assertEquals("வணக்கம்", out.get(0).text());
        assertEquals("காலை\nவணக்கம்", out.get(1).text());
        assertEquals(2.0, out.get(1).start(), 1e-9);
    }

    @Test
    void translationMissingItemFails() {
        List<Cue> cues = List.of(new Cue(0, 1, "a"), new Cue(1, 2, "b"));
        try {
            CueTranslation.apply(cues, CueTranslation.parse("[{\"i\":0,\"t\":\"x\"}]"));
            throw new AssertionError("expected failure");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("subtitle 2"));
        }
    }
}
