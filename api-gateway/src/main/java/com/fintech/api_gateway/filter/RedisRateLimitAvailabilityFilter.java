package com.fintech.api_gateway.filter;

import com.fintech.api_gateway.config.GatewayRedisMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisRateLimitAvailabilityFilter
        implements GlobalFilter, Ordered {

    private final ReactiveRedisConnectionFactory
            redisConnectionFactory;

    private final GatewayRedisMetrics metrics;

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain
    ) {

        String path =
                exchange.getRequest()
                        .getURI()
                        .getPath();

        /*
         * Redis rate limiting currently protects only
         * Payment API requests.
         *
         * Auth and unrelated routes must not depend on
         * Redis availability.
         */
        if (!path.startsWith("/api/payments/")) {

            return chain.filter(exchange);
        }

        return redisConnectionFactory
                .getReactiveConnection()
                .ping()
                .timeout(
                        Duration.ofSeconds(2)
                )
                .flatMap(
                        pong ->
                                chain.filter(
                                        exchange
                                )
                )
                .onErrorResume(
                        ex -> {

                            metrics.recordRedisFailure();

                            log.error(
                                    "Gateway Redis unavailable component=rateLimiter action=FAIL_CLOSED path={} failure={}",
                                    path,
                                    ex.getMessage()
                            );

                            exchange
                                    .getResponse()
                                    .setStatusCode(
                                            HttpStatus
                                                    .SERVICE_UNAVAILABLE
                                    );

                            return exchange
                                    .getResponse()
                                    .setComplete();
                        }
                );
    }

    @Override
    public int getOrder() {

        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}