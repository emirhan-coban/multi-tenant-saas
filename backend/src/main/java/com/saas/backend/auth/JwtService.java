package com.saas.backend.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;
    private final long refreshExpirationDays;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-minutes}") long expirationMinutes,
            @Value("${app.jwt.refresh-expiration-days:30}") long refreshExpirationDays
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
        this.refreshExpirationDays = refreshExpirationDays;
    }

    public String generateAccessToken(UUID userId, UUID tenantId, String role) {
        return generateToken(userId, tenantId, role, expirationMinutes, ChronoUnit.MINUTES, "access");
    }

    public String generateRefreshToken(UUID userId, UUID tenantId, String role) {
        return generateToken(userId, tenantId, role, refreshExpirationDays, ChronoUnit.DAYS, "refresh");
    }

    private String generateToken(UUID userId, UUID tenantId, String role, long expiration, ChronoUnit unit, String type) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId.toString())
                .claim("role", role)
                .claim("type", type)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plus(expiration, unit)))
                .signWith(key)
                .compact();
    }

    public io.jsonwebtoken.Claims parseRefreshToken(String token) {
        var claims = parseToken(token);
        if (!"refresh".equals(claims.get("type", String.class))) {
            throw new JwtException("Not a refresh token");
        }
        return claims;
    }

    public io.jsonwebtoken.Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}