
package com.leenax.studio.access;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class ActorDataSource implements DataSource {

    static final String ACT_SQL = """
            select set_config('role', case when ? = 'system' then 'none' else 'leenax_app' end, false),
                   set_config('leenax.user_id', coalesce(u.id::text, ?, ''), false),
                   set_config('leenax.mode', case when ? <> 'user' then ?
                                                  when u.id is null then 'anon'
                                                  when u.role = 'ADMIN' then 'admin' else 'user' end, false)
              from (select 1) d
              left join users u on ? = 'user' and u.status = 'ACTIVE' and (u.id::text = ? or lower(u.email) = lower(?))
            """;

    private static final String SYSTEM_SQL = """
            select set_config('role', case when ? = 'system' then 'none' else 'leenax_app' end, false),
                   set_config('leenax.user_id', coalesce(?, ''), false),
                   set_config('leenax.mode', ?, false)
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
        if ("user".equals(a.mode())) {
            try (PreparedStatement ps = c.prepareStatement(ACT_SQL)) {
                Object[] params = parameters(a);
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                ps.execute();
            }
        } else {
            String id = a.userId() == null ? null : a.userId().toString();

            try (PreparedStatement ps = c.prepareStatement(SYSTEM_SQL)) {
                ps.setString(1, a.mode());
                ps.setString(2, id);
                ps.setString(3, a.mode());
                ps.execute();
            }
        }
    }

    static Object[] parameters(Actor a) {
        String mode = a.mode();
        String id = a.userId() == null ? null : a.userId().toString();
        boolean person = "user".equals(mode);

        return new Object[]{
                mode,
                person ? null : id,
                mode,
                mode,
                mode,
                person ? id : null,
                person ? a.email() : null
        };
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
        if (type.isInstance(this)) {
            return (T) this;
        }
        return target.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || target.isWrapperFor(type);
    }
}
