package com.leenax.studio.storyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.autoedit.TimelineBuilder;
import com.leenax.studio.characters.StoryCharacter;
import com.leenax.studio.editor.TimelineDocument;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardTest {

    private final ObjectMapper json = new ObjectMapper();

    static <T> T withId(T entity, UUID id) {
        try {
            Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static StoryCharacter character(String name, String appearance, String clothing) {
        StoryCharacter c = withId(new StoryCharacter(name), UUID.randomUUID());
        c.setAppearance(appearance);
        c.setClothing(clothing);
        return c;
    }

    @Test
    void parsesScenesNormalisesCameraWordsAndCollectsSpeakers() throws Exception {
        var plan = StoryboardPlanning.parse(json.readTree("""
                {"style":"warm film look","scenes":[
                  {"title":"Arrival","description":"Kani walks into the night market","narration":"இரவு சந்தை.",
                   "dialogue":[{"character":"Meena","line":"Welcome!"},{"character":"","line":"dropped"}],
                   "durationSeconds":45,"cameraAngle":"Close-up","cameraMovement":"dolly in","lighting":"neon","characters":["Kani"]},
                  {"title":"","description":"","narration":"","dialogue":[]},
                  {"title":"Stall","description":"Close on the glass","durationSeconds":"x","cameraAngle":"dutch angle"}]}"""));
        assertEquals("warm film look", plan.style());
        assertEquals(2, plan.scenes().size());
        var first = plan.scenes().get(0);
        assertEquals("CLOSE_UP", first.cameraAngle());
        assertEquals("DOLLY_IN", first.cameraMovement());
        assertEquals(30.0, first.durationSeconds());            // capped
        assertEquals(1, first.dialogue().size());
        assertEquals(List.of("Kani", "Meena"), first.characterNames());
        assertNull(plan.scenes().get(1).cameraAngle());         // unknown angle dropped
        assertEquals(5.0, plan.scenes().get(1).durationSeconds());
        assertThrows(IllegalArgumentException.class, () -> StoryboardPlanning.parse(json.readTree("{\"scenes\":[]}")));
    }

    @Test
    void matchesCharacterNamesLooselyAndOnlyOnce() {
        var kani = character("Kani Raj", "short curly hair", "blue kurta");
        var meena = character("Meena", null, null);
        var ids = StoryboardPlanning.matchCharacters(List.of("kani  raj", "MEENA", "Kani Raj", "Stranger"), List.of(kani, meena));
        assertEquals(List.of(kani.getId().toString(), meena.getId().toString()), ids);
    }

    @Test
    void imagePromptCarriesStyleCastFramingAndNoText() {
        Scene s = new Scene(UUID.randomUUID(), 0);
        s.setDescription("Kani tastes jigarthanda at a crowded stall");
        s.setCameraAngle("CLOSE_UP");
        s.setLighting("warm neon");
        var kani = character("Kani", "short curly hair, mid-20s", "blue kurta");
        String p = StoryboardPlanning.imagePrompt(s, List.of(kani), "35mm film look", TimelineBuilder.Format.PORTRAIT);
        assertTrue(p.startsWith("35mm film look. Kani tastes jigarthanda"));
        assertTrue(p.contains("Kani: short curly hair, mid-20s; wearing blue kurta"));
        assertTrue(p.contains("Match the reference images"));
        assertTrue(p.contains("close up shot"));
        assertTrue(p.contains("9:16 vertical frame"));
        assertTrue(p.endsWith("No text, captions, logos or watermarks."));
        s.setImagePrompt("  my own prompt ");
        assertEquals("my own prompt", StoryboardPlanning.imagePrompt(s, List.of(kani), "x", TimelineBuilder.Format.LANDSCAPE));

        s.setCameraMovement("DOLLY_IN");
        String v = StoryboardPlanning.videoPrompt(s, List.of(), null);
        assertTrue(v.contains("Camera: dolly in, close up shot"));
        assertTrue(v.endsWith("No on-screen text."));
    }

    @Test
    void dialogueBecomesVoiceStudioTurnsAndScenesStretchToFitSpeech() {
        String d = StoryboardPlanning.dialogueScript(List.of(Map.of("character", "Kani: the cook", "line", "Two\nglasses, please")));
        assertEquals("Kani  the cook: Two glasses, please", d);
        assertEquals(5.0, StoryboardPlanning.length(5, 3));
        assertEquals(7.4, StoryboardPlanning.length(5, 7), 1e-9);
        assertEquals(5.0, StoryboardPlanning.length(5, 0));
    }

    @Test
    void duplicateKeepsTheStillButNotTheGeneratedVideo() {
        Scene s = withId(new Scene(UUID.randomUUID(), 2), UUID.randomUUID());
        s.setTitle("Stall");
        s.setImageMediaId(UUID.randomUUID());
        s.setVideoMediaId(UUID.randomUUID());
        s.setVideoJobId(UUID.randomUUID());
        Scene c = s.copy(3);
        assertEquals("Stall (copy)", c.getTitle());
        assertEquals(s.getImageMediaId(), c.getImageMediaId());
        assertNull(c.getVideoMediaId());
        assertNull(c.getVideoJobId());
        assertEquals(3, c.getPosition());
    }

    private static MediaAsset asset(MediaKind kind, long ms) {
        MediaAsset a = withId(new MediaAsset(kind, MediaSource.AI_GENERATED, "x", "x/x", 1, "c", "k"), UUID.randomUUID());
        a.setDurationMs(ms);
        return a;
    }

    @Test
    void exportBuildsAValidTimelineFromVideosStillsCardsNarrationAndDialogue() throws Exception {
        UUID board = UUID.randomUUID();
        MediaAsset clip = asset(MediaKind.VIDEO, 4000);           // shorter than the scene: the still fills the rest
        MediaAsset talk = asset(MediaKind.AUDIO, 6200);
        MediaAsset music = asset(MediaKind.AUDIO, 30_000);
        UUID still = UUID.randomUUID();
        UUID narrationFile = UUID.randomUUID();

        Scene a = withId(new Scene(board, 0), UUID.randomUUID());
        a.setDurationSeconds(6);
        a.setVideoMediaId(clip.getId());
        a.setImageMediaId(still);
        a.setNarration("Welcome to Madurai at night");
        Scene b = withId(new Scene(board, 1), UUID.randomUUID());
        b.setDurationSeconds(3);
        b.setDialogueMediaId(talk.getId());
        b.setImageMediaId(UUID.randomUUID());
        Scene c = withId(new Scene(board, 2), UUID.randomUUID());
        c.setTitle("The end");
        c.setDurationSeconds(4);

        Map<UUID, MediaAsset> assets = new HashMap<>(Map.of(clip.getId(), clip, talk.getId(), talk));
        Map<String, double[]> narration = Map.of(a.getId().toString(), new double[]{0.0, 7.0});
        var plan = StoryboardService.plan(List.of(a, b, c), assets, narration, TimelineBuilder.Format.LANDSCAPE, true, narrationFile, music);

        // scene A stretches to the narration (7.4 s), B to its dialogue (6.6 s), C keeps 4 s
        assertEquals(7.4 + 6.6 + 4.0, plan.total(), 1e-9);
        var summary = TimelineDocument.check(json.valueToTree(plan.document()));
        assertEquals(1920, summary.width());
        assertTrue(summary.mediaIds().containsAll(List.of(clip.getId(), still, talk.getId(), narrationFile, music.getId())));
        assertTrue(plan.notes().get(0).contains("1 scene has no image or video"));

        @SuppressWarnings("unchecked")
        var tracks = (List<Map<String, Object>>) plan.document().get("tracks");
        var scenes = clips(tracks, "Scenes");
        assertEquals(4.0, num(scenes.get(0).get("duration")), 1e-9);            // the generated clip
        assertEquals(0, ((Number) scenes.get(0).get("volume")).intValue());
        assertEquals(3.4, num(scenes.get(1).get("duration")), 1e-9);            // the still fills the rest of A
        assertEquals(7.4, num(scenes.get(2).get("start")), 1e-9);               // B starts after A
        var voice = clips(tracks, "Narration");
        assertEquals(1, voice.size());
        assertEquals(7.0, num(voice.get(0).get("duration")), 1e-9);
        assertFalse(clips(tracks, "Captions").isEmpty());
        assertEquals(1, clips(tracks, "Dialogue").size());
        assertEquals(1, clips(tracks, "Titles").size());                        // the card for C
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> clips(List<Map<String, Object>> tracks, String name) {
        return tracks.stream().filter(t -> name.equals(t.get("name"))).findFirst()
                .map(t -> (List<Map<String, Object>>) t.get("clips")).orElseThrow();
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }
}
