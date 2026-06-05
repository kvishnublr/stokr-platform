package com.stokr.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long accessTtlSeconds;
    private final String issuer;

    public JwtService(
            @Value("${stokr.security.jwt.secret}") String secret,
            @Value("${stokr.security.jwt.access-ttl-seconds:900}") long accessTtlSeconds,
            @Value("${stokr.security.jwt.issuer:stokr-platform}") String issuer
    ) {
        byte[] keyBytes = deriveKey(secret);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtlSeconds = accessTtlSeconds;
        this.issuer = issuer;
    }

    public String createAccessToken(UUID userId, String email, String scope) {
        if (userId == null) {
            throw new IllegalArgumentException("userId cannot be null when creating access token");
        }
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTtlSeconds);
        return Jwts.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claim("email", email)
                .claim("scope", scope)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(secretKey)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static byte[] deriveKey(String secret) {
        try {
            if (secret.startsWith("base64:")) {
                return Decoders.BASE64.decode(secret.substring("base64:".length()));
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
