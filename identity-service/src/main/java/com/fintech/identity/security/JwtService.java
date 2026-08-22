package com.fintech.identity.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey getSigningKey() {

        return Keys.hmacShaKeyFor(
                secret.getBytes()
        );
    }

    public String generateToken(
            String subject,
            Map<String, Object> claims
    ) {

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(
                        new Date()
                )
                .expiration(
                        new Date(
                                System.currentTimeMillis()
                                        + expiration
                        )
                )
                .signWith(
                        getSigningKey()
                )
                .compact();
    }

    public String extractUsername(
            String token
    ) {

        Claims claims =
                Jwts.parser()
                        .verifyWith(
                                getSigningKey()
                        )
                        .build()
                        .parseSignedClaims(
                                token
                        )
                        .getPayload();

        return claims.getSubject();
    }
}