package com.leenax.studio.ai.provider;

/**
 * A normalised video generation request. Option values (aspect ratio,
 * resolution) are already in the provider's own format, taken from the model
 * definition, so adapters pass them through rather than translating.
 */
public record VideoRequest(
        String clientReference,
        Mode mode,
        String modelCode,
        String prompt,
        String negativePrompt,
        Integer durationSeconds,
        String aspectRatio,
        String resolution,
        Boolean audio,
        InputImage image) {

    public enum Mode { TEXT_TO_VIDEO, IMAGE_TO_VIDEO }

    /** Image for image-to-video. publicUrl is a signed link that is only reachable when the studio is online. */
    public record InputImage(byte[] bytes, String mimeType, String publicUrl) {
        public String base64() {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }

        public String dataUri() {
            return "data:" + mimeType + ";base64," + base64();
        }
    }
}
