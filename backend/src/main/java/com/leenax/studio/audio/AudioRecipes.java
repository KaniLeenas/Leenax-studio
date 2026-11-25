package com.leenax.studio.audio;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FFmpeg argument lists for the Voice & Audio Studio. Everything runs locally:
 * no provider account or credits needed.
 */
public final class AudioRecipes {

    public enum Operation {
        /** Spectral noise reduction plus rumble filter. */
        DENOISE,
        /** Voice EQ and gentle compression for clearer narration. */
        ENHANCE,
        /** Cuts pauses longer than ~0.6 s down to a short natural gap. */
        REMOVE_SILENCE,
        /** Loudness to -14 LUFS, the level YouTube and most platforms play at. */
        NORMALIZE
    }

    public static final String MP3 = "libmp3lame";

    private AudioRecipes() {
    }

    static String filterFor(Operation op) {
        return switch (op) {
            case DENOISE -> "highpass=f=70,afftdn=nr=12:nf=-30:tn=1";
            case ENHANCE -> "highpass=f=80,lowpass=f=14000,equalizer=f=250:t=q:w=1:g=-2,equalizer=f=3500:t=q:w=1.2:g=3,"
                    + "acompressor=threshold=-20dB:ratio=3:attack=15:release=200:makeup=2";
            case REMOVE_SILENCE -> "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.1:"
                    + "stop_periods=-1:stop_threshold=-45dB:stop_duration=0.6:stop_silence=0.3";
            case NORMALIZE -> "loudnorm=I=-14:TP=-1.5:LRA=11";
        };
    }

    /** Applies operations in a sensible order (denoise → silence → enhance → normalise), whatever order they were picked in. */
    public static List<String> process(Path in, Path out, List<Operation> ops) {
        List<String> filters = new ArrayList<>();
        for (Operation op : List.of(Operation.DENOISE, Operation.REMOVE_SILENCE, Operation.ENHANCE, Operation.NORMALIZE)) {
            if (ops.contains(op)) filters.add(filterFor(op));
        }
        if (filters.isEmpty()) throw new IllegalArgumentException("Choose at least one clean-up step");
        return List.of("-i", in.toString(), "-vn", "-af", String.join(",", filters), "-ar", "44100", "-c:a", MP3, "-b:a", "192k", out.toString());
    }

    /**
     * Speed (0.5–2.0, pitch kept) and pitch shift (±12 semitones, length kept).
     * Pitch uses the resample trick: change the sample rate, then correct the tempo.
     */
    public static List<String> speedAndPitch(Path in, Path out, double speed, double semitones) {
        double s = Math.max(0.5, Math.min(2.0, speed));
        double ratio = Math.pow(2, Math.max(-12, Math.min(12, semitones)) / 12.0);
        List<String> chain = new ArrayList<>();
        chain.add("aresample=44100");
        if (Math.abs(ratio - 1) > 1e-3) {
            chain.add(String.format(Locale.ROOT, "asetrate=%.0f", 44100 * ratio));
            chain.add("aresample=44100");
        }
        double tempo = s / ratio;
        chain.addAll(atempoChain(tempo));
        return List.of("-i", in.toString(), "-vn", "-af", String.join(",", chain), "-c:a", MP3, "-b:a", "192k", out.toString());
    }

    /** atempo accepts 0.5–2.0 per stage, so larger changes are chained. */
    static List<String> atempoChain(double tempo) {
        List<String> out = new ArrayList<>();
        double t = tempo;
        while (t > 2.0) { out.add("atempo=2.0"); t /= 2.0; }
        while (t < 0.5) { out.add("atempo=0.5"); t /= 0.5; }
        if (Math.abs(t - 1) > 1e-3) out.add(String.format(Locale.ROOT, "atempo=%.4f", t));
        return out;
    }

    /** Joins clips in order with a short silence between them (dialogue turns, long scripts). */
    public static List<String> concat(List<Path> inputs, Path out, double gapSeconds) {
        List<String> args = new ArrayList<>();
        for (Path p : inputs) {
            args.add("-i");
            args.add(p.toString());
        }
        StringBuilder graph = new StringBuilder();
        int n = inputs.size();
        for (int i = 0; i < n; i++) {
            graph.append(String.format(Locale.ROOT, "[%d:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=mono", i));
            if (gapSeconds > 0 && i < n - 1) graph.append(String.format(Locale.ROOT, ",apad=pad_dur=%.2f", gapSeconds));
            graph.append(String.format(Locale.ROOT, "[a%d];", i));
        }
        for (int i = 0; i < n; i++) graph.append(String.format(Locale.ROOT, "[a%d]", i));
        graph.append(String.format(Locale.ROOT, "concat=n=%d:v=0:a=1[out]", n));
        args.addAll(List.of("-filter_complex", graph.toString(), "-map", "[out]", "-c:a", MP3, "-b:a", "192k", out.toString()));
        return args;
    }

    /**
     * Narration over background music. The music loops to the narration's length,
     * fades in and out, and dips automatically while someone is speaking (ducking).
     */
    public static List<String> mixWithMusic(Path voice, Path music, Path out, double musicVolumeDb, boolean duck,
                                            double fadeSeconds, double voiceDurationSeconds) {
        double fade = Math.max(0, Math.min(10, fadeSeconds));
        double fadeOutStart = Math.max(0, voiceDurationSeconds - fade);
        String musicChain = String.format(Locale.ROOT,
                "[1:a]aresample=44100,volume=%.1fdB%s%s[m]",
                Math.max(-40, Math.min(6, musicVolumeDb)),
                fade > 0 ? String.format(Locale.ROOT, ",afade=t=in:st=0:d=%.2f", fade) : "",
                fade > 0 && voiceDurationSeconds > 0 ? String.format(Locale.ROOT, ",afade=t=out:st=%.2f:d=%.2f", fadeOutStart, fade) : "");
        String graph = duck
                ? musicChain + ";[0:a]aresample=44100,asplit=2[v][sc];"
                  + "[m][sc]sidechaincompress=threshold=0.03:ratio=8:attack=40:release=500[ducked];"
                  + "[v][ducked]amix=inputs=2:duration=first:dropout_transition=0:normalize=0,alimiter=limit=0.95[out]"
                : musicChain + ";[0:a]aresample=44100[v];[v][m]amix=inputs=2:duration=first:dropout_transition=0:normalize=0,alimiter=limit=0.95[out]";
        return List.of("-i", voice.toString(), "-stream_loop", "-1", "-i", music.toString(),
                "-filter_complex", graph, "-map", "[out]", "-c:a", MP3, "-b:a", "192k", out.toString());
    }

    /** Audio track of a video as MP3 (for clean-up, mixing and subtitles). */
    public static List<String> extractAudio(Path video, Path out) {
        return List.of("-i", video.toString(), "-vn", "-ac", "2", "-ar", "44100", "-c:a", MP3, "-b:a", "192k", out.toString());
    }

    /** Small mono MP3 for speech-to-text uploads (about 21 MB per hour). */
    public static List<String> forTranscription(Path in, Path out) {
        return List.of("-i", in.toString(), "-vn", "-ac", "1", "-ar", "16000", "-c:a", MP3, "-b:a", "48k", out.toString());
    }

    /** Converts provider audio (WAV, etc.) to MP3 so every voice file looks the same. */
    public static List<String> toMp3(Path in, Path out) {
        return List.of("-i", in.toString(), "-vn", "-ar", "44100", "-c:a", MP3, "-b:a", "192k", out.toString());
    }
}
