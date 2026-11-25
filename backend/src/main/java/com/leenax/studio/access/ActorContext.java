package com.leenax.studio.access;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Decides who the current thread acts for:
 * <ol>
 *   <li>an explicit {@link #runAs} (background workers running a job for its owner);</li>
 *   <li>a web request with a signed-in person: that person;</li>
 *   <li>a web request without one: nobody (sees no content);</li>
 *   <li>anything else (start-up, migrations, schedulers): the system.</li>
 * </ol>
 */
public final class ActorContext {

    private static final ThreadLocal<Actor> OVERRIDE = new ThreadLocal<>();

    private ActorContext() {
    }

    public static Actor current() {
        Actor explicit = OVERRIDE.get();
        if (explicit != null) return explicit;
        boolean inRequest = RequestContextHolder.getRequestAttributes() != null;
        var context = SecurityContextHolder.getContext();
        Authentication auth = context == null ? null : context.getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName())
                && auth.getPrincipal() != null && !"anonymousUser".equals(auth.getPrincipal())) {
            return Actor.user(auth.getName());
        }
        return inRequest ? Actor.ANONYMOUS : Actor.SYSTEM;
    }

    /** Runs {@code work} as {@code actor}. Call it outside any open transaction: the setting is read when a connection is taken. */
    public static <T> T call(Actor actor, Supplier<T> work) {
        Actor previous = OVERRIDE.get();
        OVERRIDE.set(actor);
        try {
            return work.get();
        } finally {
            if (previous == null) OVERRIDE.remove();
            else OVERRIDE.set(previous);
        }
    }

    public static void runAs(Actor actor, Runnable work) {
        call(actor, () -> {
            work.run();
            return null;
        });
    }

    /** For workers: run the job as its owner (or as the system when it has none). */
    public static void runForOwner(UUID ownerId, Runnable work) {
        runAs(Actor.worker(ownerId), work);
    }
}
