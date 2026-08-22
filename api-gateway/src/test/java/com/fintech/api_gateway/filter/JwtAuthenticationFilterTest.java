package com.fintech.api_gateway.filter;

import com.fintech.api_gateway.security.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private Claims claims;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {

        filter =
                new JwtAuthenticationFilter(
                        jwtService
                );
    }

    @Test
    void missingTokenOnProtectedRouteReturnsUnauthorizedAndStopsChain() {

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest
                                .post(
                                        "/api/payments/pay"
                                )
                                .build()
                );

        AtomicBoolean called =
                new AtomicBoolean(false);

        WebFilterChain chain =
                ignored -> {

                    called.set(true);

                    return Mono.empty();
                };

        filter.filter(
                exchange,
                chain
        ).block();

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                exchange
                        .getResponse()
                        .getStatusCode()
        );

        assertFalse(
                called.get()
        );
    }

    @Test
    void invalidTokenReturnsUnauthorizedAndStopsChain() {

        when(
                jwtService.extractClaims(
                        "bad-token"
                )
        ).thenThrow(
                new IllegalArgumentException(
                        "invalid JWT"
                )
        );

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest
                                .post(
                                        "/api/payments/pay"
                                )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer bad-token"
                                )
                                .build()
                );

        AtomicBoolean called =
                new AtomicBoolean(false);

        filter.filter(
                exchange,
                ignored -> {

                    called.set(true);

                    return Mono.empty();
                }
        ).block();

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                exchange
                        .getResponse()
                        .getStatusCode()
        );

        assertFalse(
                called.get()
        );
    }

    @Test
    void publicLoginDoesNotRequireJwtAndStripsForgedHeaders() {

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest
                                .post(
                                        "/api/auth/login"
                                )
                                .header(
                                        JwtAuthenticationFilter
                                                .USER_ID_HEADER,
                                        UUID.randomUUID()
                                                .toString()
                                )
                                .header(
                                        JwtAuthenticationFilter
                                                .USER_ROLES_HEADER,
                                        "ADMIN"
                                )
                                .build()
                );

        AtomicReference<ServerWebExchange>
                forwarded =
                new AtomicReference<>();

        filter.filter(
                exchange,
                current -> {

                    forwarded.set(
                            current
                    );

                    return Mono.empty();
                }
        ).block();

        assertNotNull(
                forwarded.get()
        );

        assertNull(
                forwarded
                        .get()
                        .getRequest()
                        .getHeaders()
                        .getFirst(
                                JwtAuthenticationFilter
                                        .USER_ID_HEADER
                        )
        );

        assertNull(
                forwarded
                        .get()
                        .getRequest()
                        .getHeaders()
                        .getFirst(
                                JwtAuthenticationFilter
                                        .USER_ROLES_HEADER
                        )
        );
    }

    @Test
    void validTokenReplacesForgedIdentityHeaders() {

        UUID tokenUserId =
                UUID.randomUUID();

        UUID forgedUserId =
                UUID.randomUUID();

        when(
                jwtService.extractClaims(
                        "valid-token"
                )
        ).thenReturn(
                claims
        );

        when(
                claims.get(
                        "userId"
                )
        ).thenReturn(
                tokenUserId.toString()
        );

        when(
                claims.get(
                        "roles"
                )
        ).thenReturn(
                List.of("USER")
        );

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest
                                .post(
                                        "/api/payments/pay"
                                )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer valid-token"
                                )
                                .header(
                                        JwtAuthenticationFilter
                                                .USER_ID_HEADER,
                                        forgedUserId
                                                .toString()
                                )
                                .header(
                                        JwtAuthenticationFilter
                                                .USER_ROLES_HEADER,
                                        "ADMIN"
                                )
                                .build()
                );

        AtomicReference<ServerWebExchange>
                forwarded =
                new AtomicReference<>();

        filter.filter(
                exchange,
                current -> {

                    forwarded.set(
                            current
                    );

                    return Mono.empty();
                }
        ).block();

        assertEquals(
                tokenUserId.toString(),
                forwarded
                        .get()
                        .getRequest()
                        .getHeaders()
                        .getFirst(
                                JwtAuthenticationFilter
                                        .USER_ID_HEADER
                        )
        );

        assertEquals(
                "USER",
                forwarded
                        .get()
                        .getRequest()
                        .getHeaders()
                        .getFirst(
                                JwtAuthenticationFilter
                                        .USER_ROLES_HEADER
                        )
        );
    }
}