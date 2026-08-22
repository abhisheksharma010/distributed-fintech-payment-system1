package com.fintech.api_gateway.filter;

import com.fintech.api_gateway.config.GatewayRedisMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedisRateLimitAvailabilityFilterTest {

    @Test
    void paymentRequestReturns503WhenRedisIsUnavailable() {

        ReactiveRedisConnectionFactory factory =
                mock(
                        ReactiveRedisConnectionFactory.class
                );

        ReactiveRedisConnection connection =
                mock(
                        ReactiveRedisConnection.class
                );

        GatewayRedisMetrics metrics =
                mock(
                        GatewayRedisMetrics.class
                );

        when(
                factory.getReactiveConnection()
        ).thenReturn(
                connection
        );

        when(
                connection.ping()
        ).thenReturn(
                Mono.error(
                        new RuntimeException(
                                "Redis down"
                        )
                )
        );

        RedisRateLimitAvailabilityFilter filter =
                new RedisRateLimitAvailabilityFilter(
                        factory,
                        metrics
                );

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest
                                .get(
                                        "/api/payments/"
                                                +
                                                java.util.UUID
                                                        .randomUUID()
                                )
                                .build()
                );

        GatewayFilterChain chain =
                mock(
                        GatewayFilterChain.class
                );

        filter.filter(
                exchange,
                chain
        ).block();

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                exchange
                        .getResponse()
                        .getStatusCode()
        );

        verify(
                chain,
                never()
        ).filter(any());

        verify(metrics)
                .recordRedisFailure();
    }

    @Test
    void paymentRequestContinuesWhenRedisIsHealthy() {

        ReactiveRedisConnectionFactory factory =
                mock(
                        ReactiveRedisConnectionFactory.class
                );

        ReactiveRedisConnection connection =
                mock(
                        ReactiveRedisConnection.class
                );

        GatewayRedisMetrics metrics =
                mock(
                        GatewayRedisMetrics.class
                );

        when(
                factory.getReactiveConnection()
        ).thenReturn(
                connection
        );

        when(
                connection.ping()
        ).thenReturn(
                Mono.just("PONG")
        );

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest
                                .get(
                                        "/api/payments/"
                                                +
                                                java.util.UUID
                                                        .randomUUID()
                                )
                                .build()
                );

        GatewayFilterChain chain =
                mock(
                        GatewayFilterChain.class
                );

        when(
                chain.filter(exchange)
        ).thenReturn(
                Mono.empty()
        );

        RedisRateLimitAvailabilityFilter filter =
                new RedisRateLimitAvailabilityFilter(
                        factory,
                        metrics
                );

        filter.filter(
                exchange,
                chain
        ).block();

        verify(chain)
                .filter(exchange);

        verify(
                metrics,
                never()
        ).recordRedisFailure();
    }

    @Test
    void nonPaymentRouteDoesNotDependOnRedis() {

        ReactiveRedisConnectionFactory factory =
                mock(
                        ReactiveRedisConnectionFactory.class
                );

        GatewayRedisMetrics metrics =
                mock(
                        GatewayRedisMetrics.class
                );

        RedisRateLimitAvailabilityFilter filter =
                new RedisRateLimitAvailabilityFilter(
                        factory,
                        metrics
                );

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest
                                .post(
                                        "/api/auth/login"
                                )
                                .build()
                );

        GatewayFilterChain chain =
                mock(
                        GatewayFilterChain.class
                );

        when(
                chain.filter(exchange)
        ).thenReturn(
                Mono.empty()
        );

        filter.filter(
                exchange,
                chain
        ).block();

        verify(chain)
                .filter(exchange);

        verifyNoInteractions(factory);
    }
}