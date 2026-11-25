package com.leenax.studio.autoedit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.credits.UsageRecorder;
import com.leenax.studio.ai.provider.MediaTypes.Transcript;
import com.leenax.studio.ai.provider.MediaTypes.Word;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.ai.provider.TextProvider;
import com.leenax.studio.autoedit.AutoEditDtos.AutoEditRequest;
import com.leenax.studio.autoedit.TimelineBuilder.TextLook;
import com.leenax.studio.autoedit.TimelineBuilder.TrackKind;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.editor.EditorDtos.CreateTimelineRequest;
import com.leenax.studio.editor.TimelineService;
import com.leenax.studio.images.ImageDtos.GenerateRequest;
import com.leenax.studio.images.ImageService;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.subtitles.Cue;
import com.leenax.studio.subtitles.CueBuilder;
import com.leenax.studio.voice.VoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one automatic edit from start to finish and saves the result as editor
 * timelines. Progress is written to the job as it goes; a cancel is noticed
 * between steps.
 */
@Component
public class AutoEditProcessor {

    private static final Logger log = LoggerFactory.getLogger(AutoEditProcessor.class);
    static final int MAX_AI_IMAGES = 12;
    static final int MAX_DESCRIBE = 30;

    private final AutoEditJobRepository jobs;
    private final AccountService accounts;
    private final ProviderRegistry registry;
    private final MediaService media;
    private final MediaAnalysisService analysis;
    private final VoiceService voice;
    private final ImageService images;
    private final TimelineService timelines;
    private final UsageRecorder usage;
    private final ObjectMapper mapper;

    public AutoEditProcessor(AutoEditJobRepository jobs, AccountService accounts, ProviderRegistry registry, MediaService media,
                             MediaAnalysisService analysis, VoiceService voice, ImageService images, TimelineService timelines,
                             UsageRecorder usage, ObjectMapper mapper) {
        this.jobs = jobs;
        this.accounts = accounts;
        this.registry = registry;
        this.media = media;
        this.analysis = analysis;
        this.voice = voice;
        this.images = images;
        this.timelines = timelines;
        this.usage = usage;
        this.mapper = mapper;
    }

    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("Cancelled");
        }
    }

    /** Shared state of one run: the job id, its settings, notes for the user and the progress writer. */
    final class Run {
        final UUID jobId;
        final AutoEditRequest req;
        final List<String> notes = new ArrayList<>();

        Run(UUID jobId, AutoEditRequest req) {
            this.jobId = jobId;
            this.req = req;
        }

        /** Saves progress; stops the run if the job was cancelled meanwhile. */
        void step(String text, double progress) {
            AutoEditJob j = jobs.findById(jobId).orElseThrow(Cancelled::new);
            if (j.getStatus() != AutoEditJob.Status.RUNNING) throw new Cancelled();
            j.setStep(text);
            j.setProgress(progress);
            jobs.save(j);
        }
    }

    public void process(UUID id) {
        AutoEditJob job = jobs.findById(id).orElse(null);
        if (job == null || !job.isActive()) return;
        if (job.getAttempts() >= 2) {
            finish(id, AutoEditJob.Status.FAILED, null, "The server restarted twice during this edit. Run it again.");
            return;
        }
        job.setAttempts(job.getAttempts() + 1);
        job.setStatus(AutoEditJob.Status.RUNNING);
        job.setStartedAt(Instant.now());
        job.setStep("Starting");
        job = jobs.save(job);

        Run run = new Run(id, mapper.convertValue(job.getSettings(), AutoEditRequest.class));
        try {
            List<UUID> created = switch (job.getMode()) {
                case TALKING_HEAD -> talkingHead(run);
                case HIGHLIGHTS -> highlights(run);
                case SCRIPT_TO_VIDEO -> scriptToVideo(run);
            };
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timelineIds", created.stream().map(UUID::toString).toList());
            result.put("notes", run.notes);
            finish(id, AutoEditJob.Status.COMPLETED, result, null);
        } catch (Cancelled e) {
            log.info("Auto edit {} cancelled", id);
        } catch (ApiException | ProviderException | IllegalArgumentException e) {
            finish(id, AutoEditJob.Status.FAILED, Map.of("notes", run.notes), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Auto edit {} failed", id, e);
            finish(id, AutoEditJob.Status.FAILED, Map.of("notes", run.notes), "Something went wrong: " + e.getMessage());
        }
    }

    private void finish(UUID id, AutoEditJob.Status status, Map<String, Object> result, String error) {
        jobs.findById(id).ifPresent(j -> {
            if (j.getStatus() == AutoEditJob.Status.CANCELLED) return;
            j.setStatus(status);
            j.setResult(result);
            j.setErrorMessage(error);
            j.setStep(status == AutoEditJob.Status.COMPLETED ? "Done" : "Stopped");
            if (status == AutoEditJob.Status.COMPLETED) j.setProgress(1);
            j.setCompletedAt(Instant.now());
            j.setLockedUntil(null);
            jobs.save(j);
        });
    }

    // ==== talking head =============================================================================

    List<UUID> talkingHead(Run run) {
        AutoEditRequest r = run.req;
        var stt = accounts.forUse(r.transcriptionAccountId());
        List<MediaAsset> clips = ordered(r.footageIds());
        JumpCut.Options opts = new JumpCut.Options(r.maxPause() == null ? 0.45 : r.maxPause(), 0.12, 0.3, r.removeFillers(),
                JumpCut.DEFAULT_FILLERS);

        var b = new TimelineBuilder(r.format(), 30);
        var text = b.track(TrackKind.TEXT, "Captions", 1);
        var video = b.track(TrackKind.VIDEO, "Video", 1);
        var music = b.track(TrackKind.AUDIO, "Music", 1);
        List<Word> allWords = new ArrayList<>();
        double cursor = 0;
        double removed = 0;
        int cut = 0;
        for (int i = 0; i < clips.size(); i++) {
            MediaAsset clip = clips.get(i);
            run.step("Listening to " + clip.getOriginalFilename(), 0.05 + 0.75 * i / clips.size());
            Transcript t = analysis.transcript(clip.getId(), stt, r.language());
            double dur = seconds(clip);
            List<JumpCut.Range> ranges = t.words() == null || t.words().isEmpty() ? List.of() : JumpCut.keepRanges(t.words(), dur, opts);
            if (ranges.isEmpty()) {
                run.notes.add("No speech was found in “" + clip.getOriginalFilename() + "”, so it was kept whole.");
                ranges = List.of(new JumpCut.Range(0, dur));
            }
            for (JumpCut.Range range : ranges) {
                var c = b.media(video, clip.getId(), "VIDEO", cursor, range.length(), range.start());
                // Alternate a slight zoom on consecutive cuts so jump cuts feel intentional.
                if (r.punchIn() && cut++ % 2 == 1) c.put("scale", 1.1);
                cursor += range.length();
            }
            if (t.words() != null) allWords.addAll(JumpCut.retime(t.words(), ranges, cursor - JumpCut.total(ranges)));
            removed += Math.max(0, dur - JumpCut.total(ranges));
        }
        run.step("Building the timeline", 0.85);
        if (r.captions() && !allWords.isEmpty()) {
            boolean portrait = r.format() == TimelineBuilder.Format.PORTRAIT;
            b.captions(text, CueBuilder.fromWords(allWords, portrait ? 24 : 42), portrait);
        }
        addMusic(b, music, r, cursor, false);
        run.notes.add(String.format(Locale.ROOT, "Removed %.0f seconds of pauses%s across %d cuts.", removed,
                r.removeFillers() ? " and filler words" : "", cut == 0 ? countClips(video) : cut));
        return List.of(save(r.name(), b, r.projectId()));
    }

    // ==== highlights ===============================================================================

    List<UUID> highlights(Run run) {
        AutoEditRequest r = run.req;
        var stt = accounts.forUse(r.transcriptionAccountId());
        MediaAsset source = ordered(r.footageIds()).get(0);
        run.step("Listening to " + source.getOriginalFilename(), 0.05);
        Transcript t = analysis.transcript(source.getId(), stt, r.language());
        if (t.words() == null || t.words().size() < 20) throw ApiException.badRequest("There isn't enough speech in this video to pick highlights");

        int count = r.highlightCount() == null ? 3 : r.highlightCount();
        int min = r.minSeconds() == null ? 20 : r.minSeconds();
        int max = r.maxSeconds() == null ? 60 : r.maxSeconds();
        run.step("Finding the best moments", 0.4);
        String transcript = HighlightPicker.timestamped(t.words(), 60_000);
        JsonNode json = askJson(run, Prompts.pickHighlights(count, min, max, r.language()),
                "Video length: " + Math.round(seconds(source)) + " seconds.\n\nTranscript:\n" + transcript);
        List<HighlightPicker.Highlight> picks = HighlightPicker.snap(HighlightPicker.parse(json), t.words(), min, max, count);
        if (picks.isEmpty()) throw ApiException.badRequest("The AI didn't find usable moments. Try a different model or lengths.");
        if (picks.size() < count) run.notes.add("Found " + picks.size() + " of " + count + " requested moments.");

        List<UUID> out = new ArrayList<>();
        JumpCut.Options tighten = new JumpCut.Options(0.6, 0.15, 0.3, false, JumpCut.DEFAULT_FILLERS);
        for (int i = 0; i < picks.size(); i++) {
            var h = picks.get(i);
            run.step("Building short " + (i + 1) + " of " + picks.size(), 0.6 + 0.35 * i / picks.size());
            List<Word> inside = t.words().stream().filter(w -> w.start() >= h.start() && w.end() <= h.end()).toList();
            List<JumpCut.Range> ranges = JumpCut.keepRanges(inside, h.end(), tighten);
            if (ranges.isEmpty()) ranges = List.of(new JumpCut.Range(h.start(), h.end()));

            var b = new TimelineBuilder(r.format(), 30);
            var text = b.track(TrackKind.TEXT, "Text", 1);
            var video = b.track(TrackKind.VIDEO, "Video", 1);
            var music = b.track(TrackKind.AUDIO, "Music", 1);
            double cursor = 0;
            for (JumpCut.Range range : ranges) {
                b.media(video, source.getId(), "VIDEO", cursor, range.length(), range.start());
                cursor += range.length();
            }
            String title = h.title().isBlank() ? "Highlight " + (i + 1) : h.title();
            var titleClip = b.text(text, title, 0, Math.min(3, cursor), TextLook.TITLE);
            titleClip.put("y", r.format() == TimelineBuilder.Format.PORTRAIT ? 0.16 : 0.12);
            if (r.captions()) {
                boolean portrait = r.format() == TimelineBuilder.Format.PORTRAIT;
                var cues = CueBuilder.fromWords(JumpCut.retime(inside, ranges, 0), portrait ? 22 : 42);
                // keep the first caption clear of the title
                b.captions(text, cues, portrait);
            }
            addMusic(b, music, r, cursor, false);
            out.add(save(r.name() + " · " + title, b, r.projectId()));
        }
        return out;
    }

    // ==== script to video ==========================================================================

    List<UUID> scriptToVideo(Run run) {
        AutoEditRequest r = run.req;
        boolean hasText = r.textAccountId() != null && r.textModel() != null && !r.textModel().isBlank();
        boolean hasScript = r.script() != null && !r.script().isBlank();

        // 1. Script → scenes
        run.step(hasScript ? "Splitting your script into scenes" : "Writing the script", 0.05);
        ScenePlan.Parsed plan;
        if (hasText) {
            String system = hasScript ? Prompts.splitScript()
                    : Prompts.writeScript(r.language(), r.targetSeconds() == null ? 60 : r.targetSeconds(), r.style());
            String user = hasScript ? r.script() : "Topic: " + r.topic();
            try {
                plan = ScenePlan.parse(askJson(run, system, user));
            } catch (IllegalArgumentException e) {
                if (!hasScript) throw e;
                run.notes.add("The AI couldn't split the script, so it was split by paragraph.");
                plan = new ScenePlan.Parsed("", ScenePlan.splitPlainly(r.script()));
            }
        } else {
            plan = new ScenePlan.Parsed("", ScenePlan.splitPlainly(r.script()));
        }
        List<ScenePlan.Scene> scenes = plan.scenes();
        if (scenes.isEmpty()) throw ApiException.badRequest("The script is empty");

        // 2. Narration → scene timing
        List<double[]> timing = new ArrayList<>();
        UUID narrationId = null;
        if (r.voice() != null) {
            run.step("Recording the voice-over (" + scenes.size() + " scenes)", 0.15);
            var narration = voice.narrateScenes(r.voice(), r.language(), r.voiceStyle(),
                    scenes.stream().map(ScenePlan.Scene::narration).toList(), 0.35, r.name() + " narration", r.projectId());
            narrationId = narration.media().getId();
            timing.addAll(narration.parts());
        } else {
            double cps = "TA".equals(r.language()) ? 11 : 14;
            double t = 0;
            for (ScenePlan.Scene s : scenes) {
                double d = Math.max(2.5, s.narration().replaceAll("\\[pause[^]]*]", "").length() / cps);
                timing.add(new double[]{t, d});
                t += d + 0.35;
            }
            run.notes.add("No voice was chosen, so scene lengths are estimated from reading speed.");
        }
        // Each scene's picture runs until the next scene starts (covers the gap between narration parts).
        List<Double> sceneLengths = new ArrayList<>();
        for (int i = 0; i < timing.size(); i++) {
            double end = i + 1 < timing.size() ? timing.get(i + 1)[0] : timing.get(i)[0] + timing.get(i)[1] + 0.6;
            sceneLengths.add(Math.round((end - timing.get(i)[0]) * 1000) / 1000.0);
        }

        // 3. Footage catalogue
        List<MediaAsset> footage = new ArrayList<>(ordered(r.footageIds() == null ? List.of() : r.footageIds()));
        Map<String, MediaAsset> byRef = new LinkedHashMap<>();
        Map<String, String> descriptions = new HashMap<>();
        AccountService.Usable textAcc = hasText ? accounts.forUse(r.textAccountId()) : null;
        for (int i = 0; i < footage.size(); i++) byRef.put("F" + (i + 1), footage.get(i));
        if (r.describeFootage() && textAcc != null) {
            int n = 0;
            for (var e : byRef.entrySet()) {
                if (n >= MAX_DESCRIBE) break;
                run.step("Looking at " + e.getValue().getOriginalFilename(), 0.3 + 0.2 * n / Math.max(1, byRef.size()));
                try {
                    String d = analysis.describe(e.getValue().getId(), textAcc, r.textModel());
                    if (d != null) descriptions.put(e.getKey(), d);
                } catch (ProviderException ex) {
                    run.notes.add("Couldn't look at “" + e.getValue().getOriginalFilename() + "”: " + ex.getMessage());
                }
                n++;
            }
        }

        // 4. AI images for a timeline without footage (optional)
        if (footage.isEmpty() && r.imageAccountId() != null) {
            int n = Math.min(scenes.size(), MAX_AI_IMAGES);
            for (int i = 0; i < n; i++) {
                run.step("Creating image " + (i + 1) + " of " + n, 0.35 + 0.25 * i / n);
                ScenePlan.Scene s = scenes.get(i);
                String prompt = (s.visual().isBlank() ? s.narration() : s.visual()) + ". Cinematic, no text, no watermark.";
                try {
                    boolean openai = accounts.get(r.imageAccountId()).getProvider() == com.leenax.studio.ai.provider.ProviderCode.OPENAI;
                    var made = images.generate(new GenerateRequest(r.imageAccountId(), r.imageModel(), prompt,
                            openai ? openAiSize(r.format()) : null, openai ? null : aspect(r.format()), openai ? null : "1K",
                            1, null, false, null, r.projectId()));
                    if (!made.images().isEmpty()) {
                        MediaAsset a = media.findAsset(made.images().get(0).id());
                        String ref = "F" + (byRef.size() + 1);
                        byRef.put(ref, a);
                        descriptions.put(ref, "AI image for scene " + (i + 1) + ": " + prompt);
                    }
                } catch (ProviderException | ApiException ex) {
                    run.notes.add("Image for scene " + (i + 1) + " failed: " + ex.getMessage());
                    if (i == 0) break; // likely a model/size problem; don't repeat it for every scene
                }
            }
            if (scenes.size() > MAX_AI_IMAGES) run.notes.add("Images were made for the first " + MAX_AI_IMAGES + " scenes; later scenes reuse them.");
        }

        // 5. Match shots to scenes
        List<ShotPlanner.Footage> catalogue = new ArrayList<>();
        byRef.forEach((ref, a) -> catalogue.add(new ShotPlanner.Footage(ref, a.getKind().name(), a.getKind() == MediaKind.IMAGE ? null : seconds(a))));
        Map<Integer, List<ShotPlanner.Suggestion>> suggestions = Map.of();
        if (textAcc != null && !catalogue.isEmpty()) {
            run.step("Choosing shots for each scene", 0.65);
            try {
                suggestions = ShotPlanner.parse(askJson(run, Prompts.matchShots(), shotPrompt(scenes, sceneLengths, byRef, descriptions)));
            } catch (IllegalArgumentException | ProviderException e) {
                run.notes.add("The AI couldn't match shots (" + e.getMessage() + "), so footage was placed in order.");
            }
        } else if (!catalogue.isEmpty()) {
            run.notes.add("Footage was placed in order. Choose an AI text account to match shots to what is said.");
        }
        List<List<ShotPlanner.Shot>> shots = ShotPlanner.plan(sceneLengths, catalogue, suggestions);

        // 6. Build the timeline
        run.step("Building the timeline", 0.85);
        var b = new TimelineBuilder(r.format(), 30);
        var textTrack = b.track(TrackKind.TEXT, "Text", 1);
        var captionTrack = r.captions() ? b.track(TrackKind.TEXT, "Captions", 1) : null;
        var video = b.track(TrackKind.VIDEO, "Video", 1);
        var narration = b.track(TrackKind.AUDIO, "Voice-over", 1);
        var music = b.track(TrackKind.AUDIO, "Music", 1);
        String[] motions = {"zoom-in", "pan-left", "zoom-out", "pan-right"};
        int stills = 0;
        Map<String, Object> firstClip = null;
        Map<String, Object> lastClip = null;
        for (int s = 0; s < scenes.size(); s++) {
            double t = timing.get(s)[0];
            ScenePlan.Scene scene = scenes.get(s);
            if (shots.get(s).isEmpty()) {
                // nothing to show: a title card with the scene's words
                String card = !scene.onScreenText().isBlank() ? scene.onScreenText() : shorten(scene.narration(), 60);
                b.text(textTrack, card, t, sceneLengths.get(s), TextLook.TITLE);
            }
            for (ShotPlanner.Shot shot : shots.get(s)) {
                MediaAsset a = byRef.get(shot.ref());
                var clip = b.media(video, a.getId(), a.getKind().name(), t, shot.length(), shot.sourceStart());
                if (a.getKind() == MediaKind.IMAGE) clip.put("motion", motions[stills++ % motions.length]);
                else clip.put("volume", 0); // B-roll sound off under the narration
                if (firstClip == null) firstClip = clip;
                lastClip = clip;
                t += shot.length();
            }
            if (!scene.onScreenText().isBlank() && !shots.get(s).isEmpty()) {
                b.text(textTrack, scene.onScreenText(), timing.get(s)[0] + 0.2, Math.min(3.5, sceneLengths.get(s) - 0.2), TextLook.LOWER_THIRD);
            }
            if (captionTrack != null) {
                boolean portrait = r.format() == TimelineBuilder.Format.PORTRAIT;
                b.captions(captionTrack, CaptionChunker.fromText(scene.narration(), timing.get(s)[0], timing.get(s)[1], portrait ? 24 : 42), portrait);
            }
        }
        if (firstClip != null) firstClip.put("fadeIn", 0.5);
        if (lastClip != null) lastClip.put("fadeOut", 0.8);
        String title = plan.title().isBlank() ? null : plan.title();
        if (title != null && !shots.get(0).isEmpty()) {
            b.text(textTrack, title, 0.3, Math.min(3, sceneLengths.get(0) - 0.3), TextLook.TITLE);
        }
        double total = timing.get(timing.size() - 1)[0] + sceneLengths.get(sceneLengths.size() - 1);
        if (narrationId != null) {
            double narrationLength = timing.get(timing.size() - 1)[0] + timing.get(timing.size() - 1)[1];
            b.media(narration, narrationId, "AUDIO", 0, narrationLength, 0);
        }
        addMusic(b, music, r, total, narrationId != null);
        if (title != null) run.notes.add("Title: " + title);
        run.notes.add(scenes.size() + " scenes, about " + Math.round(total) + " seconds.");
        return List.of(save(r.name(), b, r.projectId()));
    }

    String shotPrompt(List<ScenePlan.Scene> scenes, List<Double> lengths, Map<String, MediaAsset> byRef, Map<String, String> descriptions) {
        StringBuilder sb = new StringBuilder("Scenes:\n");
        for (int i = 0; i < scenes.size(); i++) {
            sb.append(i + 1).append(". (").append(String.format(Locale.ROOT, "%.1f", lengths.get(i))).append(" s) Said: ")
              .append(shorten(scenes.get(i).narration(), 300));
            if (!scenes.get(i).visual().isBlank()) sb.append(" | Should show: ").append(scenes.get(i).visual());
            sb.append('\n');
        }
        sb.append("\nFootage:\n");
        byRef.forEach((ref, a) -> {
            sb.append(ref).append(": ").append(a.getKind() == MediaKind.IMAGE ? "image" : String.format(Locale.ROOT, "video, %.1f s", seconds(a)))
              .append(", file “").append(a.getOriginalFilename()).append('”');
            String d = descriptions.get(ref);
            if (d != null) sb.append(". ").append(d.replace('\n', ' '));
            sb.append('\n');
        });
        return sb.toString();
    }

    // ==== shared ==================================================================================

    /** Asks the text model for JSON; one retry when the reply can't be read. */
    JsonNode askJson(Run run, String system, String user) {
        var acc = accounts.forUse(run.req.textAccountId());
        TextProvider text = registry.text(acc.account().getProvider()).orElseThrow(() ->
                ApiException.badRequest(acc.account().getProvider().displayName() + " can't write text. Use OpenAI, Anthropic or Gemini."));
        IllegalArgumentException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String reply;
            try {
                reply = text.complete(run.req.textModel(), system, user, acc.credentials());
            } catch (ProviderException e) {
                usage.record(acc.account(), run.req.textModel(), "TEXT", null, null, null, false, false);
                throw e;
            }
            usage.record(acc.account(), run.req.textModel(), "TEXT", null, null, null, false, true);
            try {
                return LlmJson.extract(reply);
            } catch (IllegalArgumentException e) {
                last = e;
            }
        }
        throw last;
    }

    private void addMusic(TimelineBuilder b, Map<String, Object> track, AutoEditRequest r, double total, boolean underVoice) {
        if (r.musicMediaId() == null || total <= 0) return;
        MediaAsset m = media.findAssets(List.of(r.musicMediaId())).stream().findFirst().orElse(null);
        if (m == null || m.getDurationMs() == null) return;
        double vol = r.musicVolume() != null ? r.musicVolume() : (underVoice ? 0.15 : 0.25);
        b.music(track, m.getId(), m.getDurationMs() / 1000.0, total, vol);
    }

    private UUID save(String name, TimelineBuilder b, UUID projectId) {
        String n = name.length() > 160 ? name.substring(0, 160) : name;
        return timelines.create(new CreateTimelineRequest(n, b.build(), projectId)).id();
    }

    /** Files in the order the user chose them. */
    private List<MediaAsset> ordered(List<UUID> ids) {
        Map<UUID, MediaAsset> found = new HashMap<>();
        for (MediaAsset a : media.findAssets(ids)) found.put(a.getId(), a);
        List<MediaAsset> out = new ArrayList<>();
        for (UUID id : ids) {
            MediaAsset a = found.get(id);
            if (a == null) throw ApiException.badRequest("A chosen file was deleted from the media library");
            out.add(a);
        }
        return out;
    }

    private static double seconds(MediaAsset a) {
        return a.getDurationMs() == null ? 0 : a.getDurationMs() / 1000.0;
    }

    @SuppressWarnings("unchecked")
    private static int countClips(Map<String, Object> track) {
        return ((List<Object>) track.get("clips")).size();
    }

    /** GPT Image sizes: multiples of 16, close to the video's shape. */
    public static String openAiSize(TimelineBuilder.Format f) {
        return switch (f) {
            case LANDSCAPE -> "1536x864";
            case PORTRAIT -> "864x1536";
            case SQUARE -> "1024x1024";
        };
    }

    public static String aspect(TimelineBuilder.Format f) {
        return switch (f) {
            case LANDSCAPE -> "16:9";
            case PORTRAIT -> "9:16";
            case SQUARE -> "1:1";
        };
    }

    static String shorten(String s, int max) {
        String t = s.replaceAll("\\[pause[^]]*]", "").replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max - 1).strip() + "…";
    }
}
