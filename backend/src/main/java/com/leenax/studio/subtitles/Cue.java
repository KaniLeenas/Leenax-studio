package com.leenax.studio.subtitles;

/** One subtitle: shown from {@code start} to {@code end} seconds. Text may contain a line break. */
public record Cue(double start, double end, String text) {
}
