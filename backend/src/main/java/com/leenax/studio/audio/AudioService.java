package com.leenax.studio.audio;

import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.credits.UsageRecorder;
import com.leenax.studio.ai.provider.MediaTypes.Clip;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.ai.provider.SoundProvider;
import com.leenax.studio.audio.AudioDtos.AudioResult;
import com.leenax.studio.audio.AudioDtos.CleanupRequest;
import com.leenax.studio.audio.AudioDtos.MixRequest;
import com.leenax.studio.audio.AudioDtos.SoundEffectRequest;
import com.leenax.studio.audio.AudioDtos.SpeedPitchRequest;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.TempFiles;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.media.MediaSource;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Audio clean-up, speed/pitch, music mixing and audio extraction run locally
 * with FFmpeg. Sound effects use an ElevenLabs account. Results are new audio
 * files in the media library; originals are never changed.
 */
@Service
public class AudioService {

    private static final Set<MediaKind> AUDIO_OR_VIDEO = Set.of(MediaKind.AUDIO, MediaKind.VIDEO);

    private final MediaService media;
    private final Ffmpeg ffmpeg;
    private final AccountService accounts;
    private final ProviderRegistry registry;
    private final UsageRecorder usage;

    public AudioService(MediaService media, Ffmpeg ffmpeg, AccountService accounts, ProviderRegistry registry, UsageRecorder usage) {
        this.media = media;
        this.ffmpeg = ffmpeg;
        this.accounts = accounts;
        this.registry = registry;
        this.usage = usage;
    }

    public AudioResult cleanup(CleanupRequest req) {
        try (TempFiles tmp = new TempFiles()) {
            var in = media.localCopy(req.mediaId(), AUDIO_OR_VIDEO, "Pick an audio or video file");
            tmp.track(in.path());
            var out = tmp.create(".mp3");
            ffmpeg.run(AudioRecipes.process(in.path(), out, req.operations()));
            return save(out, base(in.asset()) + " (cleaned).mp3", in.asset());
        }
    }

    public AudioResult speedPitch(SpeedPitchRequest req) {
        try (TempFiles tmp = new TempFiles()) {
            var in = media.localCopy(req.mediaId(), AUDIO_OR_VIDEO, "Pick an audio or video file");
            tmp.track(in.path());
            var out = tmp.create(".mp3");
            ffmpeg.run(AudioRecipes.speedAndPitch(in.path(), out, req.speed(), req.semitones()));
            return save(out, base(in.asset()) + " (adjusted).mp3", in.asset());
        }
    }

    public AudioResult mix(MixRequest req) {
        try (TempFiles tmp = new TempFiles()) {
            var voice = media.localCopy(req.voiceMediaId(), AUDIO_OR_VIDEO, "Pick the narration (audio or video)");
            tmp.track(voice.path());
            var music = media.localCopy(req.musicMediaId(), Set.of(MediaKind.AUDIO), "Pick a music track (audio)");
            tmp.track(music.path());
            double length = voice.asset().getDurationMs() == null ? 0 : voice.asset().getDurationMs() / 1000.0;
            var out = tmp.create(".mp3");
            ffmpeg.run(AudioRecipes.mixWithMusic(voice.path(), music.path(), out, req.musicVolumeDb(), req.duck(),
                    req.fadeSeconds(), length));
            String title = req.title() != null && !req.title().isBlank() ? req.title().strip() : base(voice.asset()) + " (with music)";
            return save(out, title + ".mp3", voice.asset());
        }
    }

    public AudioResult extract(UUID videoMediaId) {
        try (TempFiles tmp = new TempFiles()) {
            var in = media.localCopy(videoMediaId, Set.of(MediaKind.VIDEO), "Pick a video");
            tmp.track(in.path());
            var out = tmp.create(".mp3");
            ffmpeg.run(AudioRecipes.extractAudio(in.path(), out));
            return save(out, base(in.asset()) + " (audio).mp3", in.asset());
        }
    }

    public AudioResult soundEffect(SoundEffectRequest req) {
        var usable = accounts.forUse(req.accountId());
        SoundProvider provider = registry.sound(usable.account().getProvider()).orElseThrow(() ->
                ApiException.badRequest(usable.account().getProvider().displayName() + " cannot make sound effects. Use an ElevenLabs account."));
        Clip clip;
        try {
            clip = provider.soundEffect(req.prompt().strip(), req.durationSeconds(), req.loop(), usable.credentials());
        } catch (ProviderException e) {
            usage.record(usable.account(), "sound-effects", "SOUND", null, null, null, false, false);
            throw e;
        }
        try (TempFiles tmp = new TempFiles()) {
            var raw = tmp.write(clip.bytes(), TempFiles.extensionFor(clip.mimeType()));
            String name = req.prompt().strip();
            if (name.length() > 40) name = name.substring(0, 40).strip() + "…";
            MediaAsset asset = media.ingest(raw, "SFX " + name + TempFiles.extensionFor(clip.mimeType()), MediaSource.AI_GENERATED, req.projectId());
            usage.record(usable.account(), "sound-effects", "SOUND",
                    asset.getDurationMs() == null ? null : asset.getDurationMs() / 1000.0, clip.cost(), "CREDITS", false, true);
            return new AudioResult(media.toResponse(asset));
        }
    }

    private AudioResult save(java.nio.file.Path file, String filename, MediaAsset source) {
        MediaAsset asset = media.ingest(file, filename, MediaSource.AI_GENERATED, source.getProjectId());
        return new AudioResult(media.toResponse(asset));
    }

    static String base(MediaAsset a) {
        String n = a.getOriginalFilename();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
