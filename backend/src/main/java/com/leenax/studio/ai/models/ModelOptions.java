package com.leenax.studio.ai.models;

import com.leenax.studio.ai.provider.VideoRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed view over a model's JSON "options" used to validate generation
 * requests before anything is sent (and billed) at the provider.
 */
public record ModelOptions(Map<String, Object> raw) {

    public List<String> modes() {
        return strings(raw.get("modes"));
    }

    public boolean supports(VideoRequest.Mode mode) {
        return modes().contains(mode.name());
    }

    public boolean supportsAudio() {
        return Boolean.TRUE.equals(raw.get("audio"));
    }

    public boolean durationInPrompt() {
        return Boolean.TRUE.equals(raw.get("durationInPrompt"));
    }

    public boolean needsPublicImageUrl() {
        return Boolean.TRUE.equals(raw.get("publicImageUrl"));
    }

    public Integer defaultDuration() {
        Object d = raw.get("defaultDuration");
        return d instanceof Number n ? n.intValue() : null;
    }

    /** Null when any duration is accepted (or durations are not configurable). */
    @SuppressWarnings("unchecked")
    public String validateDuration(Integer seconds) {
        Object spec = raw.get("durations");
        if (!(spec instanceof Map<?, ?> m)) return null;
        if (seconds == null) return null;
        Map<String, Object> d = (Map<String, Object>) m;
        if (d.get("values") instanceof List<?> values) {
            for (Object v : values) {
                if (v instanceof Number n && n.intValue() == seconds) return null;
            }
            return "Duration must be one of " + values + " seconds for this model";
        }
        Integer min = d.get("min") instanceof Number n ? n.intValue() : null;
        Integer max = d.get("max") instanceof Number n ? n.intValue() : null;
        if ((min != null && seconds < min) || (max != null && seconds > max)) {
            return "Duration must be between " + min + " and " + max + " seconds for this model";
        }
        return null;
    }

    /** Allowed aspect-ratio values for a mode ("ALL" applies to every mode). Empty = model decides. */
    @SuppressWarnings("unchecked")
    public List<String> aspectRatios(VideoRequest.Mode mode) {
        if (!(raw.get("aspectRatios") instanceof Map<?, ?> m)) return List.of();
        Object list = ((Map<String, Object>) m).getOrDefault(mode.name(), ((Map<String, Object>) m).get("ALL"));
        return values(list);
    }

    public List<String> resolutions() {
        return values(raw.get("resolutions"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> values(Object list) {
        List<String> out = new ArrayList<>();
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> item) out.add(Objects.toString(((Map<String, Object>) item).get("value")));
                else if (o != null) out.add(o.toString());
            }
        }
        return out;
    }

    private static List<String> strings(Object list) {
        List<String> out = new ArrayList<>();
        if (list instanceof List<?> l) l.forEach(o -> out.add(String.valueOf(o)));
        return out;
    }
}
