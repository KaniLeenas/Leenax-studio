package com.leenax.studio.common;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Turns refusals from the database into answers people can act on. Row-level security
 * (V9) answers 42501 when someone writes where they may only read; the account rules
 * raise LX002 to LX003, and the approval rules (V11) LX010 to LX012. Spring and Hibernate wrap these differently depending on the
 * path, so the cause chain is searched.
 */
public final class SqlStates {

    private SqlStates() {
    }

    public static Optional<SQLException> find(Throwable t) {
        for (int depth = 0; t != null && depth < 12; depth++, t = t.getCause()) {
            if (t instanceof SQLException e && e.getSQLState() != null) return Optional.of(e);
        }
        return Optional.empty();
    }

    /** The answer for a known refusal, or empty for anything else. */
    public static Optional<ApiException> translate(Throwable t) {
        return find(t).flatMap(e -> switch (e.getSQLState()) {
            case "42501" -> Optional.of(new ApiException(ErrorCode.FORBIDDEN,
                    "You don't have permission to change this. Ask the project owner for the Editor role."));
            case "LX002" -> Optional.of(new ApiException(ErrorCode.CONFLICT,
                    "Leenax Studio needs at least one active administrator. Make someone else an administrator first."));
            case "LX003" -> Optional.of(new ApiException(ErrorCode.CONFLICT,
                    "People are deactivated, not deleted, so their history stays."));
            // E3: approvals (V11)
            case "LX010" -> Optional.of(new ApiException(ErrorCode.CONFLICT,
                    "This version can't be changed any more. Edit it to make a new version."));
            case "LX011" -> Optional.of(new ApiException(ErrorCode.FORBIDDEN,
                    "Only the project's owners and reviewers can approve, reject or ask for changes."));
            case "LX012" -> Optional.of(new ApiException(ErrorCode.FORBIDDEN,
                    "In this project the reviewer must not be the author. Ask another reviewer."));
            case "LX014" -> Optional.of(new ApiException(ErrorCode.CONFLICT,
                    "QA shows an open critical issue. Correct and recheck it, or have a reviewer dismiss it with a reason, before approving."));
            case "LX015" -> Optional.of(new ApiException(ErrorCode.CONFLICT,
                    "QA findings and proposed corrections are kept as they were made; only their status changes, along the allowed steps."));
            case "LX016" -> Optional.of(new ApiException(ErrorCode.CONFLICT, "An imported Google Trends file is kept as imported; import a new one instead."));
            case "LX018" -> Optional.of(new ApiException(ErrorCode.CONFLICT,
                    "A lesson video needs an approved presentation; a quiz video needs an approved MCQ set and SEO report."));
            case "LX020", "LX021", "LX023" -> Optional.of(new ApiException(ErrorCode.CONFLICT, firstLine(e.getMessage(), "Not authorised") + "."));
            case "LX022" -> Optional.of(new ApiException(ErrorCode.FORBIDDEN, firstLine(e.getMessage(), "Not allowed to authorise this") + "."));
            case "LX024" -> Optional.of(new ApiException(ErrorCode.CONFLICT, firstLine(e.getMessage(), "Approve the topic list first") + "."));
            case "LX019" -> Optional.of(new ApiException(ErrorCode.CONFLICT,
                    "This version has no render that passed the file checks. Render it, and approve once the checks pass."));
            case "LX017" -> Optional.of(new ApiException(ErrorCode.CONFLICT,
                    "A presentation can be approved only while its notes, the topic's academic QA report and its SEO report are approved."));
            default -> Optional.empty();
        });
    }

    /** Hibernate's answer when an update or delete touched no row. */
    public static boolean isStale(Throwable t) {
        for (int depth = 0; t != null && depth < 12; depth++, t = t.getCause()) {
            String n = t.getClass().getSimpleName();
            if (n.contains("StaleState") || n.contains("StaleObject") || n.contains("OptimisticLock")) return true;
        }
        return false;
    }

    /** PostgreSQL messages start with "ERROR: " and may carry a "Where:" tail. */
    static String firstLine(String message, String fallback) {
        if (message == null || message.isBlank()) return fallback;
        String m = message.strip();
        if (m.startsWith("ERROR:")) m = m.substring(6).strip();
        int nl = m.indexOf('\n');
        if (nl > 0) m = m.substring(0, nl).strip();
        return m.isEmpty() ? fallback : m;
    }
}
