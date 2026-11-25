package com.leenax.studio.access;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Wraps the connection pool: every connection handed out first tells PostgreSQL who
 * it acts for, and switches to the restricted {@code leenax_app} role unless it is a
 * system connection (migrations and schedulers keep the login role).
 *
 * <p>The setting is made on every checkout, so a pooled connection never carries the
 * previous user's identity. If this wrapper were ever bypassed, the policies see no
 * mode and return nothing: the failure is loud, not a leak.
 */
public class ActorDataSource implements DataSource {

    /** Tested against PostgreSQL in /tmp/sqltest/v9.py with the same parameter order. */
    static final String ACT_SQL = """
            select set_config('role', case when ? = 'system' then 'none' else 'leenax_app' end, false),
                   set_config('leenax.user_id', coalesce(u.id::text, ?, ''), false),
                   set_config('leenax.mode', case when ? <> 'user' then ?
                                                  when u.id is null then 'anon'
                                                  when u.role = 'ADMIN' then 'admin' else 'user' end, false)
              from (select 1) d
              left join users u on ? = 'user' and u.status = 'ACTIVE' and (u.id::text = ? or lower(u.email) = lower(?))
            """;

    private final DataSource target;
    private final Supplier<Actor> actor;

    public ActorDataSource(DataSource target) {
        this(target, ActorContext::current);
    }

    ActorDataSource(DataSource target, Supplier<Actor> actor) {
        this.target = target;
        this.actor = actor;
    }

    public DataSource getTarget() {
        return target;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return act(target.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return act(target.getConnection(username, password));
    }

    private Connection act(Connection c) throws SQLException {
        try {
            apply(c, actor.get());
            return c;
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    static void apply(Connection c, Actor a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ACT_SQL)) {
            Object[] params = parameters(a);
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.execute();
        }
    }

    /** The 7 parameters of {@link #ACT_SQL}, in order. */
    static Object[] parameters(Actor a) {
        String mode = a.mode();
        String id = a.userId() == null ? null : a.userId().toString();
        boolean person = "user".equals(mode);
        return new Object[]{mode, person ? null : id, mode, mode, mode, person ? id : null, person ? a.email() : null};
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return target.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        target.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        target.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return target.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return target.getParentLogger();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) return (T) this;
        return target.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || target.isWrapperFor(type);
    }
}
