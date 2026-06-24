package com.eazycount.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class AuthTokenStore {

    private static final String KEY_PREFIX = "ec:auth:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AuthTokenStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String jti, SessionUser user, long ttlMillis) {
        try {
            final String json = objectMapper.writeValueAsString(user);
            redis.opsForValue().set(key(jti), json, Duration.ofMillis(ttlMillis));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize session user", e);
        }
    }

    public Optional<SessionUser> find(String jti) {
        final String json = redis.opsForValue().get(key(jti));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SessionUser.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public void delete(String jti) {
        redis.delete(key(jti));
    }

    private static String key(String jti) {
        return KEY_PREFIX + jti;
    }
}
