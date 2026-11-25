package com.leenax.studio.access;

import java.util.UUID;

/**
 * Who a database connection acts for. PostgreSQL row-level security reads it
 * (see V9 and {@link ActorDataSource}).
 *
 * @param mode   system, worker, user or anon. A "user" is resolved by the database
 *               to admin, user or anon from the users table, so a role change or a
 *               deactivation takes effect on the next connection.
 * @param userId the person (for "worker": the owner of the job being run)
 * @param email  how a signed-in person is identified when the id isn't known
 */
public record Actor(String mode, UUID userId, String email) {

    public static final Actor SYSTEM = new Actor("system", null, null);
    public static final Actor ANONYMOUS = new Actor("anon", null, null);

    public static Actor user(String email) {
        return new Actor("user", null, email);
    }

    public static Actor user(UUID id) {
        return new Actor("user", id, null);
    }

    /** A background job run for its owner: sees what it needs, and what it creates belongs to the owner. */
    public static Actor worker(UUID ownerId) {
        return ownerId == null ? SYSTEM : new Actor("worker", ownerId, null);
    }
}
