package com.fintech.api_gateway.config;

import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RateLimitConfigTest {

    @Test
    void authenticatedPrincipalIsRateLimitIdentityNotForgedHeader() {

        UUID authenticatedUserId =
                UUID.randomUUID();

        UUID forgedUserId =
                UUID.randomUUID();

        Counter counter =
                mock(
                        Counter.class
                );

        RateLimitConfig config =
                new RateLimitConfig();

        var resolver =
                config.userKeyResolver(
                        counter
                );

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest
                                .get(
                                        "/api/payments/mine"
                                )
                                .header(
                                        "X-USER-ID",
                                        forgedUserId
                                                .toString()
                                )
                                .build()
                );

        UsernamePasswordAuthenticationToken
                authentication =
                new UsernamePasswordAuthenticationToken(
                        authenticatedUserId
                                .toString(),
                        null,
                        List.of()
                );

        String key =
                resolver
                        .resolve(exchange)
                        .contextWrite(
                                ReactiveSecurityContextHolder
                                        .withAuthentication(
                                                authentication
                                        )
                        )
                        .block();

        assertEquals(
                authenticatedUserId.toString(),
                key
        );
    }

    @Test
    void differentAuthenticatedUsersHaveDifferentKeys() {

        Counter counter =
                mock(
                        Counter.class
                );

        RateLimitConfig config =
                new RateLimitConfig();

        var resolver =
                config.userKeyResolver(
                        counter
                );

        String userA =
                UUID.randomUUID()
                        .toString();

        String userB =
                UUID.randomUUID()
                        .toString();

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest
                                .get(
                                        "/api/payments/mine"
                                )
                                .build()
                );

        String keyA =
                resolver
                        .resolve(exchange)
                        .contextWrite(
                                ReactiveSecurityContextHolder
                                        .withAuthentication(
                                                new UsernamePasswordAuthenticationToken(
                                                        userA,
                                                        null,
                                                        List.of()
                                                )
                                        )
                        )
                        .block();

        String keyB =
                resolver
                        .resolve(exchange)
                        .contextWrite(
                                ReactiveSecurityContextHolder
                                        .withAuthentication(
                                                new UsernamePasswordAuthenticationToken(
                                                        userB,
                                                        null,
                                                        List.of()
                                                )
                                        )
                        )
                        .block();

        assertEquals(
                userA,
                keyA
        );

        assertEquals(
                userB,
                keyB
        );
    }
}