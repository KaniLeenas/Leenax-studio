package com.leenax.studio.access;

import com.leenax.studio.auth.User;
import com.leenax.studio.auth.UserRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Sessions last 12 hours, but a deactivation or a role change must apply at once. On
 * each signed-in request this checks the person's current status and role (cached for
 * a few seconds): a deactivated person is signed out; a changed role replaces the one
 * in the session, so administrator-only pages follow the database.
 */
public class AccountStatusFilter implements Filter {

    static final long CACHE_MILLIS = 5_000;

    record Snapshot(boolean active, String authority, long at) {
    }

    private final Function<String, Optional<User>> lookup;
    private final SecurityContextRepository contexts;
    private final Clock clock;
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    public AccountStatusFilter(UserRepository users, SecurityContextRepository contexts) {
        this(users::findByEmailIgnoreCase, contexts, Clock.systemUTC());
    }

    AccountStatusFilter(Function<String, Optional<User>> lookup, SecurityContextRepository contexts, Clock clock) {
        this.lookup = lookup;
        this.contexts = contexts;
        this.clock = clock;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication auth = context.getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            chain.doFilter(req, res);
            return;
        }
        Snapshot now = snapshot(auth.getName());
        if (!now.active()) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Your account is no longer active\"}");
            return;
        }
        if (!hasOnly(auth, now.authority())) {
            Authentication fresh = UsernamePasswordAuthenticationToken.authenticated(auth.getName(), null,
                    List.of(new SimpleGrantedAuthority(now.authority())));
            context.setAuthentication(fresh);
            contexts.saveContext(context, request, response);
        }
        chain.doFilter(req, res);
    }

    /** Forget the cached state now, e.g. right after an administrator changes someone. */
    public void forget(String email) {
        if (email != null) cache.remove(email.toLowerCase(Locale.ROOT));
    }

    Snapshot snapshot(String email) {
        String key = email.toLowerCase(Locale.ROOT);
        long t = clock.millis();
        Snapshot s = cache.get(key);
        if (s != null && t - s.at() < CACHE_MILLIS) return s;
        Snapshot fresh = lookup.apply(email)
                .map(u -> new Snapshot(u.isActive(), "ROLE_" + u.getRole().name(), t))
                .orElse(new Snapshot(false, "", t));
        cache.put(key, fresh);
        return fresh;
    }

    static boolean hasOnly(Authentication auth, String authority) {
        var granted = auth.getAuthorities();
        if (granted == null || granted.size() != 1) return false;
        GrantedAuthority only = granted.iterator().next();
        return authority.equals(only.getAuthority());
    }
}
