package com.leenax.studio.autoedit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.provider.MediaTypes.Word;
import com.leenax.studio.editor.TimelineDocument;
import com.leenax.studio.subtitles.Cue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoEditLogicTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<Word> words(Object... triples) {
        List<Word> out = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) out.add(new Word((String) triples[i], (Double) triples[i + 1], (Double) triples[i + 2]));
        return out;
    }

    @Test
    void jumpCutRemovesLongPausesAndFillers() {
        var w = words("Hello", 0.5, 0.9, "everyone,", 1.0, 1.5,   // phrase 1
                "um", 3.0, 3.3,                                     // filler alone
                "today", 4.0, 4.4, "வணக்கம்", 4.5, 5.0);           // phrase 2
        var ranges = JumpCut.keepRanges(w, 6.0, JumpCut.Options.defaults());
        assertEquals(2, ranges.size());
        assertEquals(0.38, ranges.get(0).start(), 1e-9);
        assertEquals(1.62, ranges.get(0).end(), 1e-9);
        assertEquals(3.88, ranges.get(1).start(), 1e-9);
        var retimed = JumpCut.retime(w, ranges, 0);
        assertEquals(4, retimed.size()); // the filler is gone
        assertEquals(0.12, retimed.get(0).start(), 1e-9);
        assertEquals(1.24 + 0.12, retimed.get(2).start(), 1e-9); // first range is 1.24 s long
        assertEquals(1.24 + 1.24, JumpCut.total(ranges), 1e-9);
    }

    @Test
    void jumpCutKeepsFillersWhenAsked() {
        var w = words("Hi", 0.0, 0.4, "um", 0.5, 0.7, "there", 0.8, 1.1);
        var o = new JumpCut.Options(0.45, 0.1, 0.3, false, JumpCut.DEFAULT_FILLERS);
        assertEquals(1, JumpCut.keepRanges(w, 2, o).size());
        assertEquals(3, JumpCut.retime(w, JumpCut.keepRanges(w, 2, o), 0).size());
    }

    @Test
    void llmJsonSurvivesFencesAndChatter() {
        var n = LlmJson.extract("Sure! Here you go:\n```json\n{\"scenes\":[{\"narration\":\"வணக்கம்\"}]}\n```");
        assertEquals("வணக்கம்", n.at("/scenes/0/narration").asText());
        assertThrows(IllegalArgumentException.class, () -> LlmJson.extract("no json here"));
        assertThrows(IllegalArgumentException.class, () -> LlmJson.extract("{\"cut\": "));
    }

    @Test
    void scenePlanParsesAndFallsBackToParagraphs() {
        var p = ScenePlan.parse(LlmJson.extract("{\"title\":\"Madurai\",\"scenes\":[{\"narration\":\"One.\",\"visual\":\"temple\"},{\"narration\":\" \"}]}"));
        assertEquals("Madurai", p.title());
        assertEquals(1, p.scenes().size());
        var plain = ScenePlan.splitPlainly("A. B. C. D.\n\nE.");
        assertEquals(List.of("A. B. C.", "D.", "E."), plain.stream().map(ScenePlan.Scene::narration).toList());
    }

    @Test
    void shotPlannerFillsEachSceneExactlyWithinSources() throws Exception {
        var footage = List.of(new ShotPlanner.Footage("F1", "VIDEO", 10.0), new ShotPlanner.Footage("F2", "VIDEO", 3.0),
                new ShotPlanner.Footage("F3", "IMAGE", null));
        var sug = ShotPlanner.parse(LlmJson.extract("""
                {"scenes":[{"scene":1,"shots":[{"f":"F1","start":8,"end":20},{"f":"F9","start":0,"end":2}]},
                           {"scene":2,"shots":[{"f":"f3","start":0,"end":3}]}]}"""));
        var plan = ShotPlanner.plan(List.of(5.0, 7.5, 4.0), footage, sug);
        for (int i = 0; i < plan.size(); i++) {
            double total = plan.get(i).stream().mapToDouble(ShotPlanner.Shot::length).sum();
            assertEquals(List.of(5.0, 7.5, 4.0).get(i), total, 0.06, "scene " + (i + 1));
            for (var s : plan.get(i)) {
                var f = footage.stream().filter(x -> x.ref().equals(s.ref())).findFirst().orElseThrow();
                if (f.seconds() != null) assertTrue(s.sourceStart() + s.length() <= f.seconds() + 1e-6, s.toString());
            }
        }
        assertEquals("F1", plan.get(0).get(0).ref());
        assertEquals(8.0, plan.get(0).get(0).sourceStart());
        assertEquals("F3", plan.get(1).get(0).ref()); // case-insensitive refs
        // no footage at all → empty shots (the builder shows a title card instead)
        assertTrue(ShotPlanner.plan(List.of(3.0), List.of(), Map.of()).get(0).isEmpty());
    }

    @Test
    void highlightsSnapToSentencesAndDontOverlap() {
        List<Word> w = new ArrayList<>();
        double t = 0;
        for (int i = 0; i < 120; i++) {
            String text = (i % 10 == 9) ? "end." : "word";
            w.add(new Word(text, t, t + 0.4));
            t += 0.5;
        }
        var picks = HighlightPicker.parse(LlmJson.extract("""
                {"highlights":[{"start":5.2,"end":30,"title":"First"},{"start":10,"end":20,"title":"Overlaps"},{"start":40,"end":58,"title":"Second"}]}"""));
        var snapped = HighlightPicker.snap(picks, w, 10, 30, 3);
        assertEquals(2, snapped.size());
        assertEquals("First", snapped.get(0).title());
        assertEquals(4.9, snapped.get(0).start(), 1e-9); // word after "end." at 4.5–4.9 starts at 5.0, minus 0.1
        assertTrue(snapped.get(0).length() <= 30.3);
        assertTrue(HighlightPicker.timestamped(w, 10_000).startsWith("[0.0] word"));
    }

    @Test
    void captionChunksCoverTheNarration() {
        List<Cue> cues = CaptionChunker.fromText("வணக்கம் நண்பர்களே! [pause 1s] இன்று நாம் மதுரைக்கு போகிறோம்.", 10, 6, 20);
        assertTrue(cues.size() >= 2);
        assertEquals(10.0, cues.get(0).start(), 1e-9);
        assertEquals(16.0, cues.get(cues.size() - 1).end(), 1e-6);
        assertFalse(cues.stream().anyMatch(c -> c.text().contains("[pause")));
    }

    @Test
    void builderMakesValidEditorDocuments() throws Exception {
        UUID video = UUID.randomUUID();
        UUID music = UUID.randomUUID();
        var b = new TimelineBuilder(TimelineBuilder.Format.PORTRAIT, 30);
        var text = b.track(TimelineBuilder.TrackKind.TEXT, "Captions", 1);
        var v = b.track(TimelineBuilder.TrackKind.VIDEO, "Video", 1);
        var a = b.track(TimelineBuilder.TrackKind.AUDIO, "Music", 1);
        b.media(v, video, "VIDEO", 0, 4, 2.5);
        b.text(text, "தலைப்பு", 0, 3, TimelineBuilder.TextLook.TITLE);
        b.captions(text, List.of(new Cue(0, 1.5, "hello"), new Cue(1.5, 1.5, "")), true);
        b.music(a, music, 3, 10, 0.2);
        var doc = b.build();
        var summary = TimelineDocument.check(JSON.valueToTree(doc));
        assertEquals(10.0, summary.durationSeconds());
        assertEquals(1080, summary.width());
        @SuppressWarnings("unchecked")
        var musicClips = (List<Map<String, Object>>) a.get("clips");
        assertEquals(4, musicClips.size()); // 3+3+3+1 s
        assertEquals(1.0, ((Number) musicClips.get(3).get("duration")).doubleValue(), 1e-9);
        assertTrue(((Number) musicClips.get(3).get("fadeOut")).doubleValue() > 0);
    }
}
