package com.leenax.studio.access;

import com.leenax.studio.auth.User;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.common.SqlStates;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void parametersFollowTheSqlInOrder() {
        // mode, user_id fallback, mode, mode, mode, id to look up, email to look up
        assertArrayEquals(new Object[]{"system", null, "system", "system", "system", null, null}, ActorDataSource.parameters(Actor.SYSTEM));
        assertArrayEquals(new Object[]{"anon", null, "anon", "anon", "anon", null, null}, ActorDataSource.parameters(Actor.ANONYMOUS));
        assertArrayEquals(new Object[]{"user", null, "user", "user", "user", null, "kani@leenax.lk"}, ActorDataSource.parameters(Actor.user("kani@leenax.lk")));
        assertArrayEquals(new Object[]{"user", null, "user", "user", "user", ID.toString(), null}, ActorDataSource.parameters(Actor.user(ID)));
        // a worker is never looked up: it acts with the owner's id for attribution only
        assertArrayEquals(new Object[]{"worker", ID.toString(), "worker", "worker", "worker", null, null}, ActorDataSource.parameters(Actor.worker(ID)));
        assertSame(Actor.SYSTEM, Actor.worker(null));
        assertEquals(7, ActorDataSource.ACT_SQL.chars().filter(c -> c == '?').count());
    }

    /** A connection that records what was sent to it, and can be told to fail. */
    static final class Recorded {
        final List<Object> params = new ArrayList<>();
        final List<String> sql = new ArrayList<>();
        final AtomicInteger closed = new AtomicInteger();
        boolean fail;

        Connection connection() {
            PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, m, args) -> switch (m.getName()) {
                        case "setObject" -> { params.add(args[1]); yield null; }
                        case "execute" -> { if (fail) throw new SQLException("role \"leenax_app\" does not exist", "42704"); yield true; }
                        default -> null;
                    });
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, m, args) -> switch (m.getName()) {
                        case "prepareStatement" -> { sql.add((String) args[0]); yield ps; }
                        case "close" -> { closed.incrementAndGet(); yield null; }
                        default -> null;
                    });
        }
    }

    private static javax.sql.DataSource pool(Recorded r) {
        return (javax.sql.DataSource) Proxy.newProxyInstance(AccessTest.class.getClassLoader(), new Class<?>[]{javax.sql.DataSource.class},
                (proxy, m, args) -> m.getName().equals("getConnection") ? r.connection() : null);
    }

    @Test
    void everyConnectionSaysWhoItActsFor() throws Exception {
        Recorded r = new Recorded();
        ActorDataSource ds = new ActorDataSource(pool(r), () -> Actor.user("asha@leenax.lk"));
        ds.getConnection();
        ds.getConnection();
        assertEquals(2, r.sql.size());
        assertEquals(ActorDataSource.ACT_SQL, r.sql.get(0));
        assertEquals(List.of("user", "user", "user", "user", "asha@leenax.lk"),
                r.params.stream().filter(java.util.Objects::nonNull).limit(5).toList());
        assertEquals(0, r.closed.get());
    }

    @Test
    void aConnectionThatCannotBeSetIsClosedAndRefused() {
        Recorded r = new Recorded();
        r.fail = true;
        ActorDataSource ds = new ActorDataSource(pool(r), () -> Actor.SYSTEM);
        assertThrows(SQLException.class, ds::getConnection);
        assertEquals(1, r.closed.get());   // never handed out half-configured
    }

    @Test
    void runAsNestsAndRestores() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        ActorContext.runAs(Actor.worker(a), () -> {
            assertEquals(a, ActorContext.current().userId());
            ActorContext.runAs(Actor.worker(b), () -> assertEquals(b, ActorContext.current().userId()));
            assertEquals(a, ActorContext.current().userId());
        });
        assertEquals("system", ActorContext.current().mode());   // no request, no override
        assertThrows(IllegalStateException.class, () -> ActorContext.runAs(Actor.worker(a), () -> {
            throw new IllegalStateException("job failed");
        }));
        assertEquals("system", ActorContext.current().mode());   // restored after a failure too
    }

    @Test
    void databaseRefusalsBecomeClearAnswers() {
        RuntimeException rls = new RuntimeException("could not execute statement",
                new SQLException("ERROR: new row violates row-level security policy for table \"media_assets\"", "42501"));
        ApiException e = SqlStates.translate(rls).orElseThrow();
        assertEquals(ErrorCode.FORBIDDEN, e.code());
        assertTrue(e.getMessage().contains("Editor role"));

        assertEquals(ErrorCode.CONFLICT, SqlStates.translate(new SQLException("x", "LX002")).orElseThrow().code());
        assertFalse(SqlStates.translate(new SQLException("duplicate key", "23505")).isPresent());
        assertFalse(SqlStates.translate(new RuntimeException("no sql here")).isPresent());

        class StaleStateException extends RuntimeException {
        }
        assertTrue(SqlStates.isStale(new RuntimeException(new StaleStateException())));
        assertFalse(SqlStates.isStale(new RuntimeException("other")));
    }

    @Test
    void accountsMoveThroughTheirStates() {
        User u = User.invited("devi@leenax.lk", "Devi", User.Role.MEMBER, false, ID);
        assertEquals(User.Status.INVITED, u.getStatus());
        assertNull(u.getPasswordHash());
        assertFalse(u.canCreateProjects());
        u.activate("hash");
        assertTrue(u.isActive());
        assertTrue(u.getActivatedAt() != null);
        u.deactivate();
        assertEquals(User.Status.DEACTIVATED, u.getStatus());
        u.reactivate();
        assertEquals(User.Status.ACTIVE, u.getStatus());   // had a password: straight back to active

        User never = User.invited("e@leenax.lk", "E", User.Role.MEMBER, true, ID);
        never.deactivate();
        never.reactivate();
        assertEquals(User.Status.INVITED, never.getStatus());   // never set a password: invited again

        User admin = User.firstAdmin("k@leenax.lk", "Kani", "hash");
        assertTrue(admin.isAdmin());
        assertTrue(admin.canCreateProjects());
    }

    @Test
    void statusIsReadFreshAfterAFewSeconds() {
        AtomicInteger lookups = new AtomicInteger();
        User u = User.firstAdmin("k@leenax.lk", "Kani", "hash");
        long[] now = {1_000_000};
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        AccountStatusFilter f = new AccountStatusFilter(e -> {
            lookups.incrementAndGet();
            return Optional.of(u);
        }, null, clock);
        assertEquals("ROLE_ADMIN", f.snapshot("K@leenax.lk").authority());
        f.snapshot("k@leenax.lk");
        assertEquals(1, lookups.get());          // cached, case-insensitive
        u.setRole(User.Role.MEMBER);
        now[0] += AccountStatusFilter.CACHE_MILLIS + 1;
        assertEquals("ROLE_MEMBER", f.snapshot("k@leenax.lk").authority());
        u.deactivate();
        f.forget("k@leenax.lk");
        assertFalse(f.snapshot("k@leenax.lk").active());

        AccountStatusFilter none = new AccountStatusFilter(e -> Optional.empty(), null, clock);
        assertFalse(none.snapshot("gone@leenax.lk").active());
    }
}
