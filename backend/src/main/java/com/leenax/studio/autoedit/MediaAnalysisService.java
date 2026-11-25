package com.leenax.studio.autoedit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.credits.UsageRecorder;
import com.leenax.studio.ai.provider.MediaTypes.Sample;
import com.leenax.studio.ai.provider.MediaTypes.Segment;
import com.leenax.studio.ai.provider.MediaTypes.Transcript;
import com.leenax.studio.ai.provider.MediaTypes.Word;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.ai.provider.TextProvider;
import com.leenax.studio.ai.provider.TranscriptionProvider;
import com.leenax.studio.audio.AudioRecipes;
import com.leenax.studio.audio.Ffmpeg;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.TempFiles;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Transcripts (word timestamps) and short visual descriptions of footage,
 * cached per file so repeated auto-edits cost nothing extra.
 */
@Service
public class MediaAnalysisService {

    private final MediaAnalysisRepository analyses;
    private final MediaService media;
    private final Ffmpeg ffmpeg;
    private final ProviderRegistry registry;
    private final UsageRecorder usage;
    private final ObjectMapper mapper;

    public MediaAnalysisService(MediaAnalysisRepository analyses, MediaService media, Ffmpeg ffmpeg, ProviderRegistry registry,
                                UsageRecorder usage, ObjectMapper mapper) {
        this.analyses = analyses;
        this.media = media;
        this.ffmpeg = ffmpeg;
        this.registry = registry;
        this.usage = usage;
        this.mapper = mapper;
    }

    /** The file's transcript with word timings; transcribed (once) if needed. */
    public Transcript transcript(UUID mediaId, AccountService.Usable account, String language) {
        MediaAnalysis a = analyses.findById(mediaId).orElseGet(() -> new MediaAnalysis(mediaId));
        if (a.getTranscript() != null) return fromMap(a.getTranscript(), a.getLanguage());

        TranscriptionProvider provider = registry.transcription(account.account().getProvider()).orElseThrow(() ->
                ApiException.badRequest(account.account().getProvider().displayName() + " cannot transcribe. Use an ElevenLabs or OpenAI account."));
        Transcript t;
        Double seconds;
        try (TempFiles tmp = new TempFiles()) {
            var in = media.localCopy(mediaId, Set.of(MediaKind.VIDEO, MediaKind.AUDIO), "Pick video or audio files with speech");
            tmp.track(in.path());
            seconds = in.asset().getDurationMs() == null ? null : in.asset().getDurationMs() / 1000.0;
            Path audio = tmp.create(".mp3");
            ffmpeg.run(AudioRecipes.forTranscription(in.path(), audio));
            if (Files.size(audio) > provider.maxBytes()) {
                throw ApiException.badRequest("“" + in.asset().getOriginalFilename() + "” is too long for "
                        + account.account().getProvider().displayName() + ". Use an ElevenLabs account for long videos.");
            }
            try {
                t = provider.transcribe(audio, "audio/mpeg", language == null ? null : language.toLowerCase(Locale.ROOT), account.credentials());
            } catch (ProviderException e) {
                usage.record(account.account(), "speech-to-text", "TRANSCRIPTION", seconds, null, null, false, false);
                throw e;
            }
        } catch (IOException e) {
            throw new ApiException(com.leenax.studio.common.ErrorCode.INTERNAL_ERROR, "Could not prepare the audio");
        }
        usage.record(account.account(), "speech-to-text", "TRANSCRIPTION", seconds, null, null, false, true);
        a.setTranscript(toMap(t), t.language(), account.account().getProvider().name());
        analyses.save(a);
        return t;
    }

    /** One-sentence description of what the footage shows, from 3 frames (or the image itself). */
    public String describe(UUID mediaId, AccountService.Usable account, String model) {
        MediaAnalysis a = analyses.findById(mediaId).orElseGet(() -> new MediaAnalysis(mediaId));
        if (a.getDescription() != null && model.equals(a.getDescriptionModel())) return a.getDescription();
        TextProvider text = registry.text(account.account().getProvider()).orElseThrow(() ->
                ApiException.badRequest(account.account().getProvider().displayName() + " can't describe footage"));
        List<Sample> frames = frames(mediaId);
        if (frames.isEmpty()) return null;
        String out;
        try {
            out = text.completeWithImages(model, Prompts.describeFootage(), "Describe this footage.", frames, account.credentials());
        } catch (ProviderException e) {
            usage.record(account.account(), model, "TEXT", null, null, null, false, false);
            throw e;
        }
        usage.record(account.account(), model, "TEXT", null, null, null, false, true);
        String clean = out.strip();
        if (clean.length() > 600) clean = clean.substring(0, 600);
        a.setDescription(clean, model);
        analyses.save(a);
        return clean;
    }

    /** Small JPEG frames: start, middle and end of a video, or the image itself. */
    List<Sample> frames(UUID mediaId) {
        List<Sample> out = new ArrayList<>();
        try (TempFiles tmp = new TempFiles()) {
            var in = media.localCopy(mediaId, Set.of(MediaKind.VIDEO, MediaKind.IMAGE), "Pick video or image files");
            tmp.track(in.path());
            boolean video = in.asset().getKind() == MediaKind.VIDEO;
            double dur = in.asset().getDurationMs() == null ? 0 : in.asset().getDurationMs() / 1000.0;
            double[] at = video ? new double[]{dur * 0.15, dur * 0.5, dur * 0.85} : new double[]{0};
            int i = 0;
            for (double t : at) {
                Path f = tmp.create(".jpg");
                List<String> args = new ArrayList<>();
                if (video) {
                    args.add("-ss");
                    args.add(String.format(Locale.ROOT, "%.2f", t));
                }
                args.addAll(List.of("-i", in.path().toString(), "-frames:v", "1", "-vf", "scale=512:-2", "-q:v", "5", f.toString()));
                ffmpeg.run(args);
                if (Files.size(f) > 0) out.add(new Sample(Files.readAllBytes(f), "frame" + (++i) + ".jpg", "image/jpeg"));
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    public void forgetTranscript(UUID mediaId) {
        analyses.findById(mediaId).ifPresent(a -> {
            a.setTranscript(null, null, null);
            analyses.save(a);
        });
    }

    // ---- (de)serialisation ------------------------------------------------------

    Map<String, Object> toMap(Transcript t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", t.text());
        m.put("words", t.words() == null ? List.of() : t.words());
        m.put("segments", t.segments() == null ? List.of() : t.segments());
        return mapper.convertValue(m, Map.class);
    }

    Transcript fromMap(Map<String, Object> m, String language) {
        List<Word> words = mapper.convertValue(m.getOrDefault("words", List.of()),
                mapper.getTypeFactory().constructCollectionType(List.class, Word.class));
        List<Segment> segments = mapper.convertValue(m.getOrDefault("segments", List.of()),
                mapper.getTypeFactory().constructCollectionType(List.class, Segment.class));
        return new Transcript(language, String.valueOf(m.getOrDefault("text", "")), words, segments);
    }
}
