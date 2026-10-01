package com.fintech.api_gateway.filter;

import com.fintech.api_gateway.security.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter
        implements WebFilter, Ordered {

    public static final String USER_ID_HEADER =
            "X-USER-ID";

    public static final String USER_ROLES_HEADER =
            "X-USER-ROLES";

    private static final Set<String> PUBLIC_PATHS =
            Set.of(
                    "/api/auth/register",
                    "/api/auth/login",
                    "/actuator/health",
                    "/actuator/health/liveness",
                    "/actuator/health/readiness"
            );

    private final JwtService jwtService;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            WebFilterChain chain
    ) {

        ServerWebExchange sanitizedExchange =
                stripClientIdentityHeaders(
                        exchange
                );

        String path =
                sanitizedExchange
                        .getRequest()
                        .getURI()
                        .getPath();

        if (PUBLIC_PATHS.contains(path)) {

            return chain.filter(
                    sanitizedExchange
            );
        }

        String authHeader =
                sanitizedExchange
                        .getRequest()
                        .getHeaders()
                        .getFirst(
                                HttpHeaders.AUTHORIZATION
                        );

        if (
                authHeader == null
                        ||
                        !authHeader.startsWith(
                                "Bearer "
                        )
        ) {

            log.warn(
                    "Protected request rejected due to missing Bearer token path={}",
                    path
            );

            return unauthorized(
                    sanitizedExchange
            );
        }

        String token =
                authHeader
                        .substring(7)
                        .trim();

        if (token.isEmpty()) {

            return unauthorized(
                    sanitizedExchange
            );
        }

        try {

            Claims claims =
                    jwtService.extractClaims(
                            token
                    );

            UUID userId =
                    extractUserId(
                            claims
                    );

            Set<String> roles =
                    extractRoles(
                            claims
                    );

            if (roles.isEmpty()) {

                log.warn(
                        "JWT contains no usable roles userId={} path={}",
                        userId,
                        path
                );

                return unauthorized(
                        sanitizedExchange
                );
            }

            String rolesHeader =
                    String.join(
                            ",",
                            roles
                    );

            ServerHttpRequest trustedRequest =
                    sanitizedExchange
                            .getRequest()
                            .mutate()
                            .headers(
                                    headers -> {

                                        headers.remove(
                                                USER_ID_HEADER
                                        );

                                        headers.remove(
                                                USER_ROLES_HEADER
                                        );

                                        headers.set(
                                                USER_ID_HEADER,
                                                userId.toString()
                                        );

                                        headers.set(
                                                USER_ROLES_HEADER,
                                                rolesHeader
                                        );
                                    }
                            )
                            .build();

            ServerWebExchange trustedExchange =
                    sanitizedExchange
                            .mutate()
                            .request(
                                    trustedRequest
                            )
                            .build();

            List<SimpleGrantedAuthority>
                    authorities =
                    roles.stream()
                            .map(
                                    role ->
                                            new SimpleGrantedAuthority(
                                                    "ROLE_"
                                                            + role
                                            )
                            )
                            .toList();

            UsernamePasswordAuthenticationToken
                    authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId.toString(),
                            token,
                            authorities
                    );

            log.info(
                    "JWT authenticated userId={} roles={} path={}",
                    userId,
                    roles,
                    path
            );

            return chain
                    .filter(
                            trustedExchange
                    )
                    .contextWrite(
                            ReactiveSecurityContextHolder
                                    .withAuthentication(
                                            authentication
                                    )
                    );

        } catch (Exception ex) {

            log.warn(
                    "JWT validation failed path={} error={}",
                    path,
                    ex.getClass()
                            .getSimpleName()
            );

            return unauthorized(
                    sanitizedExchange
            );
        }
    }

    private ServerWebExchange
    stripClientIdentityHeaders(
            ServerWebExchange exchange
    ) {

        ServerHttpRequest request =
                exchange
                        .getRequest()
                        .mutate()
                        .headers(
                                headers -> {

                                    headers.remove(
                                            USER_ID_HEADER
                                    );

                                    headers.remove(
                                            USER_ROLES_HEADER
                                    );
                                }
                        )
                        .build();

        return exchange
                .mutate()
                .request(request)
                .build();
    }

    private UUID extractUserId(
            Claims claims
    ) {

        Object userId =
                claims.get(
                        "userId"
                );

        if (userId == null) {

            throw new IllegalArgumentException(
                    "JWT userId missing"
            );
        }

        return UUID.fromString(
                userId.toString()
        );
    }

    private Set<String> extractRoles(
            Claims claims
    ) {

        Object value =
                claims.get(
                        "roles"
                );

        if (
                value
                        instanceof Collection<?> collection
        ) {

            return collection
                    .stream()
                    .map(
                            Object::toString
                    )
                    .map(
                            String::trim
                    )
                    .filter(
                            role ->
                                    !role.isEmpty()
                    )
                    .collect(
                            Collectors
                                    .toUnmodifiableSet()
                    );
        }

        if (value instanceof String roles) {

            String normalized =
                    roles
                            .replace(
                                    "[",
                                    ""
                            )
                            .replace(
                                    "]",
                                    ""
                            );

            return List.of(
                            normalized.split(",")
                    )
                    .stream()
                    .map(
                            String::trim
                    )
                    .filter(
                            role ->
                                    !role.isEmpty()
                    )
                    .collect(
                            Collectors
                                    .toUnmodifiableSet()
                    );
        }

        return Set.of();
    }

    private Mono<Void> unauthorized(
            ServerWebExchange exchange
    ) {

        exchange
                .getResponse()
                .setStatusCode(
                        HttpStatus.UNAUTHORIZED
                );

        return exchange
                .getResponse()
                .setComplete();
    }
}