package com.leenax.studio.subtitles;

import java.util.List;
import java.util.Locale;

/** SRT and WebVTT writers. */
public final class SubtitleFormats {

    private SubtitleFormats() {
    }

    public static String toSrt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Cue c : cues) {
            sb.append(n++).append('\n')
              .append(time(c.start(), ',')).append(" --> ").append(time(c.end(), ',')).append('\n')
              .append(clean(c.text())).append("\n\n");
        }
        return sb.toString();
    }

    public static String toVtt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        for (Cue c : cues) {
            sb.append(time(c.start(), '.')).append(" --> ").append(time(c.end(), '.')).append('\n')
              .append(clean(c.text()).replace("-->", "->")).append("\n\n");
        }
        return sb.toString();
    }

    /** 00:01:02,345 (SRT) or 00:01:02.345 (VTT). */
    static String time(double seconds, char sep) {
        long ms = Math.round(Math.max(0, seconds) * 1000);
        long h = ms / 3_600_000;
        long m = (ms / 60_000) % 60;
        long s = (ms / 1000) % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", h, m, s, sep, ms % 1000);
    }

    /** Blank lines would end a cue early in both formats. */
    private static String clean(String text) {
        return text.replace("\r", "").replaceAll("\n\\s*\n+", "\n").strip();
    }
}
