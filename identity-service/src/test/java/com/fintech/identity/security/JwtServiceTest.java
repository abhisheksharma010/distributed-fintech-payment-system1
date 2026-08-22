package com.fintech.identity.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET =
            "my-super-secret-key-my-super-secret-key";

    @Test
    void generateTokenUsesConfiguredExpiration() {

        JwtService jwtService =
                new JwtService();

        ReflectionTestUtils.setField(
                jwtService,
                "secret",
                SECRET
        );

        ReflectionTestUtils.setField(
                jwtService,
                "expiration",
                120_000L
        );

        long before =
                System.currentTimeMillis();

        String token =
                jwtService.generateToken(
                        "user@example.com",
                        Map.of(
                                "userId",
                                "123"
                        )
                );

        long after =
                System.currentTimeMillis();

        SecretKey key =
                Keys.hmacShaKeyFor(
                        SECRET.getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        Claims claims =
                Jwts.parser()
                        .verifyWith(key)
                        .build()
                        .parseSignedClaims(
                                token
                        )
                        .getPayload();

        Date expiration =
                claims.getExpiration();

        assertNotNull(
                expiration
        );

        // JWT exp is stored with second granularity, so the serialized
        // expiration is floored to the nearest second (losing up to 999ms).
        // Allow a 1s tolerance on the lower bound to account for that.
        assertTrue(
                expiration.getTime()
                        >= before + 120_000L - 1000L
        );

        assertTrue(
                expiration.getTime()
                        <= after + 120_000L
        );
    }
}