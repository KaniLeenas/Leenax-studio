package com.leenax.studio.subtitles;

import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.credits.UsageRecorder;
import com.leenax.studio.ai.provider.MediaTypes.Transcript;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.ai.provider.TextProvider;
import com.leenax.studio.ai.provider.TranscriptionProvider;
import com.leenax.studio.audio.AudioRecipes;
import com.leenax.studio.audio.Ffmpeg;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.common.TempFiles;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.subtitles.SubtitleDtos.CreateTrackRequest;
import com.leenax.studio.subtitles.SubtitleDtos.CueDto;
import com.leenax.studio.subtitles.SubtitleDtos.TrackResponse;
import com.leenax.studio.subtitles.SubtitleDtos.TrackSummary;
import com.leenax.studio.subtitles.SubtitleDtos.TranscribeRequest;
import com.leenax.studio.subtitles.SubtitleDtos.TranslateRequest;
import com.leenax.studio.subtitles.SubtitleDtos.UpdateTrackRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Speech-to-text subtitles, translation that keeps timing, editing and SRT/VTT export. */
@Service
public class SubtitleService {

    private final SubtitleTrackRepository tracks;
    private final MediaService media;
    private final Ffmpeg ffmpeg;
    private final AccountService accounts;
    private final ProviderRegistry registry;
    private final UsageRecorder usage;

    public SubtitleService(SubtitleTrackRepository tracks, MediaService media, Ffmpeg ffmpeg, AccountService accounts,
                           ProviderRegistry registry, UsageRecorder usage) {
        this.tracks = tracks;
        this.media = media;
        this.ffmpeg = ffmpeg;
        this.accounts = accounts;
        this.registry = registry;
        this.usage = usage;
    }

    /** Extracts a small mono MP3, sends it for transcription with word timings, and builds readable cues. */
    public TrackResponse transcribe(TranscribeRequest req) {
        var usable = accounts.forUse(req.accountId());
        TranscriptionProvider provider = registry.transcription(usable.account().getProvider()).orElseThrow(() ->
                ApiException.badRequest(usable.account().getProvider().displayName() + " cannot transcribe. Use an ElevenLabs or OpenAI account."));
        Transcript transcript;
        UUID projectId;
        Double seconds;
        try (TempFiles tmp = new TempFiles()) {
            var in = media.localCopy(req.mediaId(), Set.of(MediaKind.AUDIO, MediaKind.VIDEO), "Pick a video or audio file");
            tmp.track(in.path());
            projectId = in.asset().getProjectId();
            seconds = in.asset().getDurationMs() == null ? null : in.asset().getDurationMs() / 1000.0;
            var audio = tmp.create(".mp3");
            ffmpeg.run(AudioRecipes.forTranscription(in.path(), audio));
            long size = Files.size(audio);
            if (size > provider.maxBytes()) {
                throw ApiException.badRequest("This file is too long for " + usable.account().getProvider().displayName()
                        + " in one go. Use an ElevenLabs account, or trim it into shorter parts.");
            }
            try {
                transcript = provider.transcribe(audio, "audio/mpeg", req.language(), usable.credentials());
            } catch (ProviderException e) {
                usage.record(usable.account(), "speech-to-text", "TRANSCRIPTION", seconds, null, null, false, false);
                throw e;
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not prepare the audio");
        }
        usage.record(usable.account(), "speech-to-text", "TRANSCRIPTION", seconds, null, null, false, true);

        int lineChars = req.maxLineChars() == null ? CueBuilder.DEFAULT_LINE_CHARS : req.maxLineChars();
        List<Cue> cues = transcript.words() != null && !transcript.words().isEmpty()
                ? CueBuilder.fromWords(transcript.words(), lineChars)
                : CueBuilder.fromSegments(transcript.segments() == null ? List.of() : transcript.segments(), lineChars);
        if (cues.isEmpty()) throw ApiException.badRequest("No speech was found in this file");

        String lang = normaliseLanguage(transcript.language(), req.language());
        SubtitleTrack track = tracks.save(new SubtitleTrack(req.mediaId(), projectId, lang,
                CueTranslation.languageName(lang) + " (auto)", SubtitleTrack.Source.TRANSCRIBED, cues));
        return toResponse(track);
    }

    /** Translates cue text in batches with a text model; start and end times are copied unchanged. */
    public TrackResponse translate(UUID trackId, TranslateRequest req) {
        SubtitleTrack source = find(trackId);
        var usable = accounts.forUse(req.accountId());
        TextProvider text = registry.text(usable.account().getProvider()).orElseThrow(() ->
                ApiException.badRequest(usable.account().getProvider().displayName() + " cannot translate. Use an OpenAI, Anthropic or Gemini account."));
        String target = req.targetLanguage().toLowerCase();
        String system = CueTranslation.systemPrompt(CueTranslation.languageName(target));
        List<Cue> all = source.getSegments();
        List<Cue> out = new ArrayList<>(all.size());
        for (int from = 0; from < all.size(); from += CueTranslation.BATCH) {
            List<Cue> batch = all.subList(from, Math.min(all.size(), from + CueTranslation.BATCH));
            out.addAll(translateBatch(text, req.model().strip(), system, batch, usable));
        }
        usage.record(usable.account(), req.model().strip(), "TEXT", null, null, null, false, true);
        SubtitleTrack track = tracks.save(new SubtitleTrack(source.getMediaId(), source.getProjectId(), target,
                CueTranslation.languageName(target) + " (translated)", SubtitleTrack.Source.TRANSLATED, out));
        return toResponse(track);
    }

    /** One retry when the model returns something unusable. */
    private List<Cue> translateBatch(TextProvider text, String model, String system, List<Cue> batch, AccountService.Usable usable) {
        IllegalArgumentException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String reply = text.complete(model, system, CueTranslation.userPrompt(batch, 0), usable.credentials());
            try {
                Map<Integer, String> map = CueTranslation.parse(reply);
                return CueTranslation.apply(batch, map);
            } catch (IllegalArgumentException e) {
                last = e;
            }
        }
        throw ApiException.badRequest(last.getMessage() + ". Try again or pick a different model.");
    }

    @Transactional
    public TrackResponse create(CreateTrackRequest req) {
        var asset = media.findAsset(req.mediaId());
        if (asset.getKind() == MediaKind.IMAGE) throw ApiException.badRequest("Subtitles need a video or audio file");
        String label = req.label() != null && !req.label().isBlank() ? req.label().strip() : CueTranslation.languageName(req.language());
        SubtitleTrack t = tracks.save(new SubtitleTrack(asset.getId(), asset.getProjectId(), req.language(), label,
                SubtitleTrack.Source.MANUAL, clean(req.cues() == null ? List.of() : req.cues())));
        return toResponse(t);
    }

    @Transactional(readOnly = true)
    public List<TrackSummary> list(UUID mediaId) {
        return tracks.findByMediaIdOrderByCreatedAtAsc(mediaId).stream()
                .map(t -> new TrackSummary(t.getId(), t.getMediaId(), t.getLanguage(), t.getLabel(), t.getSource(),
                        t.getSegments().size(), t.getUpdatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public TrackResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional
    public TrackResponse update(UUID id, UpdateTrackRequest req) {
        SubtitleTrack t = find(id);
        if (req.label() != null) {
            if (req.label().isBlank()) throw ApiException.badRequest("Name cannot be blank");
            t.setLabel(req.label().strip());
        }
        if (req.language() != null) t.setLanguage(req.language());
        if (req.cues() != null) t.setSegments(clean(req.cues()));
        return toResponse(t);
    }

    @Transactional
    public void delete(UUID id) {
        tracks.delete(find(id));
    }

    public record Export(String filename, String contentType, String body) {
    }

    @Transactional(readOnly = true)
    public Export export(UUID id, String format) {
        SubtitleTrack t = find(id);
        String base = media.findAsset(t.getMediaId()).getOriginalFilename().replaceAll("\\.[^.]+$", "") + "." + t.getLanguage();
        return switch (format.toLowerCase()) {
            case "srt" -> new Export(base + ".srt", "application/x-subrip; charset=utf-8", SubtitleFormats.toSrt(t.getSegments()));
            case "vtt" -> new Export(base + ".vtt", "text/vtt; charset=utf-8", SubtitleFormats.toVtt(t.getSegments()));
            default -> throw ApiException.badRequest("Choose SRT or VTT");
        };
    }

    /** Sorted, trimmed, empty cues removed, end after start. */
    static List<Cue> clean(List<CueDto> cues) {
        List<Cue> out = new ArrayList<>();
        for (CueDto c : cues) {
            String text = c.text() == null ? "" : c.text().replace("\r", "").strip();
            if (text.isEmpty()) continue;
            double start = Math.max(0, c.start());
            double end = Math.max(c.end(), start + 0.2);
            out.add(new Cue(CueBuilder.round(start), CueBuilder.round(end), text));
        }
        out.sort(Comparator.comparingDouble(Cue::start));
        return out;
    }

    /** Providers report "ta", "tam" or "tamil": store the two-letter code. */
    static String normaliseLanguage(String detected, String requested) {
        String d = detected == null ? "" : detected.strip().toLowerCase();
        if (d.startsWith("tam") || d.equals("ta")) return "ta";
        if (d.startsWith("eng") || d.equals("en")) return "en";
        if (d.length() == 2) return d;
        if (d.length() == 3 && d.matches("[a-z]{3}")) {
            for (String iso : java.util.Locale.getISOLanguages()) {
                try {
                    if (new java.util.Locale(iso).getISO3Language().equals(d)) return iso;
                } catch (java.util.MissingResourceException ignored) {
                    // skip
                }
            }
        }
        return requested != null ? requested : "en";
    }

    private SubtitleTrack find(UUID id) {
        return tracks.findById(id).orElseThrow(() -> ApiException.notFound("Subtitle track"));
    }

    private static TrackResponse toResponse(SubtitleTrack t) {
        return new TrackResponse(t.getId(), t.getMediaId(), t.getProjectId(), t.getLanguage(), t.getLabel(), t.getSource(),
                t.getSegments(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
