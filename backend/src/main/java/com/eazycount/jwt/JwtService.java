package com.eazycount.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.UUID;

@Service
@ConfigurationProperties(prefix = "spring.jwt")
public class JwtService {

    private String secret = "change-me-to-a-long-random-secret-key";
    private long accessTokenExpiration = 3_600_000L;
    private long refreshTokenExpiration = 604_800_000L;
    private String cookieName = "ec_access_token";

    private SecretKey signingKey;

    @PostConstruct
    void init() throws NoSuchAlgorithmException {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            keyBytes = MessageDigest.getInstance("SHA-256").digest(keyBytes);
        }
        signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public IssuedToken createAccessToken(String subject) {
        final String jti = UUID.randomUUID().toString();
        final Date now = new Date();
        final Date expiry = new Date(now.getTime() + accessTokenExpiration);
        final String token = Jwts.builder()
                .id(jti)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
        return new IssuedToken(token, jti, accessTokenExpiration);
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public void setAccessTokenExpiration(long accessTokenExpiration) {
        this.accessTokenExpiration = accessTokenExpiration;
    }

    public long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    public void setRefreshTokenExpiration(long refreshTokenExpiration) {
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public record IssuedToken(String token, String jti, long ttlMillis) {
    }
}
