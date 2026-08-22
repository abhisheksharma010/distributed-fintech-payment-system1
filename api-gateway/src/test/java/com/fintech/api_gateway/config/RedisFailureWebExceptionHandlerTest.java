package com.fintech.api_gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class RedisFailureWebExceptionHandlerTest {

    @Test
    void redisFailureReturnsServiceUnavailable() {

        GatewayRedisMetrics metrics =
                mock(
                        GatewayRedisMetrics.class
                );

        RedisFailureWebExceptionHandler handler =
                new RedisFailureWebExceptionHandler(
                        metrics
                );

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest
                                .get(
                                        "/api/payments/mine"
                                )
                                .build()
                );

        handler.handle(
                exchange,
                new RedisConnectionFailureException(
                        "Redis unavailable"
                )
        ).block();

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                exchange
                        .getResponse()
                        .getStatusCode()
        );

        verify(metrics)
                .recordRedisFailure();
    }
}