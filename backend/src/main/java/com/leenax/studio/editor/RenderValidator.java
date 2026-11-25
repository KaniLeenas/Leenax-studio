package com.leenax.studio.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a finished render with ffprobe (and ffmpeg's volume meter) before it counts as done:
 * the length, picture size, frame rate and frame count match the timeline; the picture is H.264
 * in 4:2:0 (yuv420p or yuvj420p); and when the timeline has sound, there is an AAC track of the same length that
 * isn't silent. A render that fails is marked failed with the reasons.
 */
@Component
public class RenderValidator {

    /** What the file should be, from the timeline and the export settings. */
    public record Expected(double durationSeconds, int width, int height, int fps, boolean audio) {
    }

    public record Result(boolean ok, List<String> problems, Map<String, Object> found, Expected expected) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok);
            m.put("checkedAt", Instant.now().toString());
            m.put("problems", problems);
            m.put("found", found);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("durationSeconds", Math.round(expected.durationSeconds() * 1000) / 1000.0);
            e.put("width", expected.width());
            e.put("height", expected.height());
            e.put("fps", expected.fps());
            e.put("audio", expected.audio());
            m.put("expected", e);
            return m;
        }
    }

    static final double DURATION_TOLERANCE = 0.5;
    static final double SILENCE_DB = -55;

    private final String ffprobe;
    private final String ffmpeg;
    private final ObjectMapper mapper;

    public RenderValidator(@Value("${leenax.media.ffprobe-path:ffprobe}") String ffprobe,
                           @Value("${leenax.media.ffmpeg-path:ffmpeg}") String ffmpeg, ObjectMapper mapper) {
        this.ffprobe = ffprobe;
        this.ffmpeg = ffmpeg;
        this.mapper = mapper;
    }

    /** What a timeline document rendered at these settings should produce. */
    public static Expected expected(Map<String, Object> document, RenderSettings settings, ObjectMapper mapper) {
        JsonNode doc = mapper.valueToTree(document);
        int w = doc.path("width").asInt(1920), h = doc.path("height").asInt(1080);
        int[] size = settings.outputSize(w, h);
        double end = 0;
        boolean audio = false;
        for (JsonNode t : doc.path("tracks")) {
            boolean muted = t.path("muted").asBoolean(false) || t.path("volume").asDouble(1) <= 0;
            for (JsonNode c : t.path("clips")) {
                end = Math.max(end, c.path("start").asDouble() + c.path("duration").asDouble());
                if (!muted && "media".equals(c.path("type").asText()) && "AUDIO".equals(c.path("kind").asText()) && c.path("volume").asDouble(1) > 0) {
                    audio = true;
                }
            }
        }
        return new Expected(end, size[0], size[1], doc.path("fps").asInt(30), audio);
    }

    public Result validate(Path file, Expected e) {
        JsonNode probe;
        try {
            String out = run(List.of(ffprobe, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.toString()));
            probe = mapper.readTree(out);
        } catch (Exception ex) {
            return new Result(false, List.of("ffprobe couldn't read the file (" + ex.getMessage() + ")"), Map.of(), e);
        }
        Double mean = null;
        if (e.audio() && hasAudio(probe)) {
            try {
                mean = meanVolume(run(List.of(ffmpeg, "-hide_banner", "-nostats", "-i", file.toString(), "-vn", "-af", "volumedetect", "-f", "null", "-")));
            } catch (Exception ex) {
                return new Result(false, List.of("The sound couldn't be measured (" + ex.getMessage() + ")"), Map.of(), e);
            }
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException ex) {
            size = 0;
        }
        return check(probe, mean, size, e);
    }

    /** The checks themselves, on ffprobe's JSON and the measured mean volume. */
    static Result check(JsonNode probe, Double meanVolumeDb, long sizeBytes, Expected e) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> found = new LinkedHashMap<>();
        JsonNode video = null, audio = null;
        for (JsonNode s : probe.path("streams")) {
            if (video == null && "video".equals(s.path("codec_type").asText())) video = s;
            if (audio == null && "audio".equals(s.path("codec_type").asText())) audio = s;
        }
        double duration = probe.path("format").path("duration").asDouble(Double.NaN);
        found.put("sizeBytes", sizeBytes);
        found.put("durationSeconds", Double.isNaN(duration) ? null : Math.round(duration * 1000) / 1000.0);
        if (sizeBytes <= 0) problems.add("The file is empty");
        if (Double.isNaN(duration)) problems.add("The file has no length");
        else if (Math.abs(duration - e.durationSeconds()) > DURATION_TOLERANCE) {
            problems.add(String.format("It is %.2f s long; the timeline is %.2f s", duration, e.durationSeconds()));
        }
        if (video == null) {
            problems.add("There is no picture");
        } else {
            int w = video.path("width").asInt(), h = video.path("height").asInt();
            double fps = rate(video.path("avg_frame_rate").asText(video.path("r_frame_rate").asText("")));
            long frames = video.path("nb_frames").asLong(-1);
            found.put("codec", video.path("codec_name").asText(null));
            found.put("pixelFormat", video.path("pix_fmt").asText(null));
            found.put("width", w);
            found.put("height", h);
            found.put("fps", Math.round(fps * 100) / 100.0);
            found.put("frames", frames < 0 ? null : frames);
            if (!"h264".equals(video.path("codec_name").asText())) problems.add("The picture isn't H.264 (" + video.path("codec_name").asText("unknown") + ")");
            // Remotion's H.264 comes out as yuvj420p (the full-range form of 4:2:0), which players and YouTube take like yuv420p.
            if (!List.of("yuv420p", "yuvj420p").contains(video.path("pix_fmt").asText())) {
                problems.add("The picture format isn't 4:2:0 (yuv420p); it's " + video.path("pix_fmt").asText("unknown"));
            }
            if (w != e.width() || h != e.height()) problems.add("The picture is " + w + "×" + h + "; expected " + e.width() + "×" + e.height());
            if (Math.abs(fps - e.fps()) > 0.01) problems.add(String.format("The frame rate is %.2f; expected %d", fps, e.fps()));
            long want = Math.round(e.durationSeconds() * e.fps());
            if (frames < 0) problems.add("The number of frames is unknown");
            else if (Math.abs(frames - want) > 2) problems.add("It has " + frames + " frames; expected about " + want);
        }
        if (e.audio()) {
            if (audio == null) {
                problems.add("There is no sound, but the timeline has narration or audio");
            } else {
                double ad = audio.path("duration").asDouble(Double.NaN);
                found.put("audioCodec", audio.path("codec_name").asText(null));
                found.put("audioChannels", audio.path("channels").asInt());
                found.put("audioSeconds", Double.isNaN(ad) ? null : Math.round(ad * 1000) / 1000.0);
                found.put("meanVolumeDb", meanVolumeDb);
                if (!"aac".equals(audio.path("codec_name").asText())) problems.add("The sound isn't AAC (" + audio.path("codec_name").asText("unknown") + ")");
                if (!Double.isNaN(ad) && !Double.isNaN(duration) && Math.abs(ad - duration) > 1.0) {
                    problems.add(String.format("The sound is %.2f s long; the picture %.2f s", ad, duration));
                }
                if (meanVolumeDb == null || meanVolumeDb < SILENCE_DB) problems.add("The sound is silent");
            }
        } else if (audio != null) {
            found.put("audioCodec", audio.path("codec_name").asText(null));
        }
        return new Result(problems.isEmpty(), problems, found, e);
    }

    static double rate(String r) {
        try {
            if (r.contains("/")) {
                String[] p = r.split("/");
                double d = Double.parseDouble(p[1]);
                return d == 0 ? 0 : Double.parseDouble(p[0]) / d;
            }
            return Double.parseDouble(r);
        } catch (Exception e) {
            return 0;
        }
    }

    static final Pattern MEAN = Pattern.compile("mean_volume:\\s*(-?[0-9.]+|-inf) dB");

    static Double meanVolume(String ffmpegOutput) {
        Matcher m = MEAN.matcher(ffmpegOutput);
        if (!m.find()) return null;
        return "-inf".equals(m.group(1)) ? Double.NEGATIVE_INFINITY : Double.parseDouble(m.group(1));
    }

    static boolean hasAudio(JsonNode probe) {
        for (JsonNode s : probe.path("streams")) if ("audio".equals(s.path("codec_type").asText())) return true;
        return false;
    }

    private static String run(List<String> cmd) throws IOException, InterruptedException {
        Path out = Files.createTempFile("leenax-check-", ".txt");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("timed out");
            }
            String text = Files.readString(out, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) throw new IOException("exit " + p.exitValue());
            return text;
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
