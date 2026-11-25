package com.leenax.studio.autoedit;

import com.leenax.studio.subtitles.Cue;
import com.leenax.studio.subtitles.CueBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Captions for narration we generated ourselves: we know the words and how long
 * the scene's audio is, so text is split into short phrases timed by their share
 * of the characters (speech rate is close to constant within one take).
 */
public final class CaptionChunker {

    private CaptionChunker() {
    }

    public static List<Cue> fromText(String text, double start, double duration, int maxChars) {
        String clean = text.replaceAll("\\[pause[^]]*]", " ").replaceAll("\\s+", " ").strip();
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : clean.split(" ")) {
            if (word.isEmpty()) continue;
            if (cur.length() > 0 && cur.length() + 1 + word.length() > maxChars) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
            if (word.matches(".*[.!?।]$") && cur.length() >= maxChars / 2) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        int total = chunks.stream().mapToInt(String::length).sum();
        List<Cue> out = new ArrayList<>();
        double t = start;
        for (String c : chunks) {
            double d = total == 0 ? duration : duration * c.length() / total;
            out.add(new Cue(CueBuilder.round(t), CueBuilder.round(t + d), c));
            t += d;
        }
        return out;
    }
}
