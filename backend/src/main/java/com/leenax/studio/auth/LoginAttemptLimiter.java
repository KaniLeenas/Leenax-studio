package com.leenax.studio.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Brute-force protection backed by Redis, so counters survive restarts.
 *
 * Two counters: one per client IP (normal lockout) and one for the owner
 * account across all IPs (stops slow distributed guessing).
 */
@Component
public class LoginAttemptLimiter {

    private static final String IP_PREFIX = "leenax:login-fail:ip:";
    private static final String ACCOUNT_KEY = "leenax:login-fail:account";
    private static final int ACCOUNT_MULTIPLIER = 4;

    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    public LoginAttemptLimiter(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public boolean isLocked(String ip) {
        return count(IP_PREFIX + ip) >= properties.maxFailedLogins()
                || count(ACCOUNT_KEY) >= (long) properties.maxFailedLogins() * ACCOUNT_MULTIPLIER;
    }

    public void recordFailure(String ip) {
        increment(IP_PREFIX + ip);
        increment(ACCOUNT_KEY);
    }

    public void recordSuccess(String ip) {
        redis.delete(IP_PREFIX + ip);
        redis.delete(ACCOUNT_KEY);
    }

    public int lockoutMinutes() {
        return properties.lockoutMinutes();
    }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private void increment(String key) {
        Long value = redis.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redis.expire(key, Duration.ofMinutes(properties.lockoutMinutes()));
        }
    }
}
