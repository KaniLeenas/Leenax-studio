package com.leenax.studio.scripts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.projects.Project;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptWritingTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesIdeasAndSkipsUntitledOnes() throws Exception {
        var ideas = ScriptWriting.parseIdeas(json.readTree("""
                {"ideas":[{"title":"Madurai street food at night","hook":"Ever tried jigarthanda at midnight?","angle":"one street, five stalls"},
                          {"title":"  ","hook":"x"}]}"""));
        assertEquals(1, ideas.size());
        assertEquals("Madurai street food at night", ideas.get(0).title());
        assertThrows(IllegalArgumentException.class, () -> ScriptWriting.parseIdeas(json.readTree("{\"ideas\":[]}")));
    }

    @Test
    void rendersAScriptTheCreatorCanEditAndKeepsItsStructure() throws Exception {
        var w = ScriptWriting.parseScript(json.readTree("""
                {"title":"ஜிகர்தண்டா","intro":"வணக்கம்!","scenes":[
                  {"heading":"The stall","narration":"இது மதுரையின் புகழ்பெற்ற கடை.","visual":"Wide shot of a busy stall at night"},
                  {"heading":"","narration":"","visual":""}],
                 "outro":"Subscribe!"}"""));
        assertEquals(1, w.scenes().size());
        String body = ScriptWriting.render(w);
        assertTrue(body.startsWith("[INTRO]\nவணக்கம்!"));
        assertTrue(body.contains("[SCENE 1: The stall]\n(Visual: Wide shot of a busy stall at night)\nஇது மதுரையின் புகழ்பெற்ற கடை."));
        assertTrue(body.endsWith("[OUTRO]\nSubscribe!"));
        @SuppressWarnings("unchecked")
        var scenes = (List<Map<String, String>>) ScriptWriting.sections(w).get("scenes");
        assertEquals("The stall", scenes.get(0).get("heading"));
    }

    @Test
    void countsOnlySpokenWordsAndPacesTamilSlower() {
        String body = "[INTRO]\nHello and welcome to the show\n\n[SCENE 1: Market]\n(Visual: market at dawn)\none two three four";
        assertEquals("Hello and welcome to the show one two three four".split(" ").length, ScriptService.words(ScriptService.spokenText(body)));
        String words = "word ".repeat(300);
        assertEquals(2.0, ScriptService.spokenMinutes(words, Project.Language.EN));
        assertTrue(ScriptService.spokenMinutes(words, Project.Language.TA) > 2.0);
    }

    @Test
    void cleansHashtagsAndKeepsOnlyRequestedPlatforms() throws Exception {
        var m = ScriptWriting.parseMetadata(json.readTree("""
                {"platforms":{"YOUTUBE":{"title":"T","description":"D","hashtags":["#madurai","madurai","street food",""]},
                              "TIKTOK":{"caption":"C","hashtags":["##fyp"]},"MYSPACE":{"caption":"no"}}}"""), List.of("YOUTUBE", "TIKTOK"));
        assertEquals(List.of("YOUTUBE", "TIKTOK"), List.copyOf(m.keySet()));
        @SuppressWarnings("unchecked")
        var yt = (Map<String, Object>) m.get("YOUTUBE");
        assertEquals(List.of("madurai", "streetfood"), yt.get("hashtags"));
        @SuppressWarnings("unchecked")
        var tt = (Map<String, Object>) m.get("TIKTOK");
        assertEquals(List.of("fyp"), tt.get("hashtags"));
    }

    @Test
    void promptsCarryLanguageLengthAndFormat() {
        String p = ScriptWriting.scriptUser("", "Filter coffee at home", ScriptWriting.VideoType.YOUTUBE_SHORT, Project.Language.TA, 1, "warm", null);
        assertTrue(p.contains("YouTube Short"));
        assertTrue(p.contains("about 1 minute"));
        assertTrue(p.contains("Tamil script"));
        assertFalse(p.contains("Working title"));
        assertEquals(ScriptWriting.VideoType.OTHER, ScriptWriting.VideoType.of("NOPE"));
    }
}
