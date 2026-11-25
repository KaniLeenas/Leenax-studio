package com.leenax.studio.common;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A ceiling on API requests per client address (one-minute windows), so a
 * runaway script or a leaked session can't hammer the server or the paid AI
 * providers behind it. Sign-in has its own stricter lockout.
 */
@Component
public class RateLimitFilter implements Filter {

    private final int perMinute;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();   // ip -> {minute, count}

    public RateLimitFilter(@Value("${leenax.security.api-requests-per-minute:600}") int perMinute) {
        this.perMinute = perMinute;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req instanceof HttpServletRequest http && res instanceof HttpServletResponse out
                && limited(http.getRequestURI()) && !allow(http.getRemoteAddr(), Instant.now().getEpochSecond() / 60)) {
            out.setStatus(429);
            out.setHeader("Retry-After", "60");
            out.setContentType("application/json");
            out.getWriter().write("{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests. Wait a minute and try again.\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    static boolean limited(String uri) {
        return uri != null && uri.startsWith("/api/") && !uri.endsWith("/events");   // live event streams stay open
    }

    /** Counts a request in the current minute; false once the ceiling is passed. */
    boolean allow(String ip, long minute) {
        String key = ip == null ? "?" : ip;
        long[] w = windows.compute(key, (k, v) -> v == null || v[0] != minute ? new long[]{minute, 1} : new long[]{minute, v[1] + 1});
        if (windows.size() > 10_000) windows.entrySet().removeIf(e -> e.getValue()[0] < minute);
        return w[1] <= perMinute;
    }
}
