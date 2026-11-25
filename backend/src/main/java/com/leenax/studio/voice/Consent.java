package com.leenax.studio.voice;

/** Voice-clone consent: the exact sentence the owner must type. */
public final class Consent {

    public static final String STATEMENT =
            "I confirm this is my own voice and I consent to creating a private AI clone of it.";

    private Consent() {
    }

    /** Case, spacing and curly apostrophes don't matter; the words do. */
    public static boolean matches(String typed) {
        if (typed == null) return false;
        String norm = typed.strip().replaceAll("\\s+", " ").replace('’', '\'');
        return norm.equalsIgnoreCase(STATEMENT);
    }
}
