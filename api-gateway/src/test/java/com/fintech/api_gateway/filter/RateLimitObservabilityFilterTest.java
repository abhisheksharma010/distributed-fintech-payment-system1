package com.fintech.api_gateway.filter;

import com.fintech.api_gateway.config.GatewayRedisMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.*;

class RateLimitObservabilityFilterTest {

    @Test
    void tooManyRequestsRecordsRateLimitMetric() {

        GatewayRedisMetrics metrics =
                mock(
                        GatewayRedisMetrics.class
                );

        RateLimitObservabilityFilter filter =
                new RateLimitObservabilityFilter(
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

        GatewayFilterChain chain =
                ignored -> {

                    exchange
                            .getResponse()
                            .setStatusCode(
                                    HttpStatus
                                            .TOO_MANY_REQUESTS
                            );

                    return Mono.empty();
                };

        filter.filter(
                exchange,
                chain
        ).block();

        verify(metrics)
                .recordRateLimitRejected();
    }
}