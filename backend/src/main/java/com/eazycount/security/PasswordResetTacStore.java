package com.eazycount.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

//Redis-backed TAC (verification code) store for password reset.
@Service
public class PasswordResetTacStore {

    private static final String CODE_PREFIX = "ec:auth:reset-tac:";
    private static final String ATTEMPTS_PREFIX = "ec:auth:reset-attempts:";
    private static final String COOLDOWN_PREFIX = "ec:auth:reset-cooldown:";

    private static final Duration CODE_TTL = Duration.ofMinutes(15);
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;

    private final StringRedisTemplate redis;

    public PasswordResetTacStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Claims the 60s resend cooldown. Returns 0 if claimed, or the remaining seconds if still locked. */
    public long tryClaimCooldown(String scope, String identity) {
        String key = COOLDOWN_PREFIX + scope + ":" + identity;
        Boolean claimed = redis.opsForValue().setIfAbsent(key, "1", COOLDOWN_TTL);
        if (Boolean.TRUE.equals(claimed)) {
            return 0;
        }
        Long ttl = redis.getExpire(key);
        return (ttl == null || ttl < 0) ? COOLDOWN_TTL.toSeconds() : ttl;
    }

    public void saveCode(String scope, String identity, String code) {
        redis.opsForValue().set(CODE_PREFIX + scope + ":" + identity, code, CODE_TTL);
        redis.delete(ATTEMPTS_PREFIX + scope + ":" + identity);
    }

    /** Verifies the code and consumes it (one-time use) on success. */
    public boolean verifyAndConsume(String scope, String identity, String code) {
        String codeKey = CODE_PREFIX + scope + ":" + identity;
        String stored = redis.opsForValue().get(codeKey);
        if (stored == null) {
            return false;
        }
        if (!stored.equals(code)) {
            registerFailure(scope, identity, codeKey);
            return false;
        }
        redis.delete(codeKey);
        redis.delete(ATTEMPTS_PREFIX + scope + ":" + identity);
        return true;
    }

    private void registerFailure(String scope, String identity, String codeKey) {
        String attemptsKey = ATTEMPTS_PREFIX + scope + ":" + identity;
        Long attempts = redis.opsForValue().increment(attemptsKey);
        redis.expire(attemptsKey, CODE_TTL);
        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            // Too many wrong guesses — invalidate the code outright.
            redis.delete(codeKey);
            redis.delete(attemptsKey);
        }
    }
}
