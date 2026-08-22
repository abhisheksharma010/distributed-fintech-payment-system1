package com.fintech.api_gateway.filter;

import com.fintech.api_gateway.config.GatewayRedisMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class RateLimitObservabilityFilter
        implements GlobalFilter, Ordered {

    private final GatewayRedisMetrics metrics;

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain
    ) {

        return chain
                .filter(exchange)
                .doFinally(
                        ignored -> {

                            if (
                                    exchange
                                            .getResponse()
                                            .getStatusCode()
                                            ==
                                            HttpStatus
                                                    .TOO_MANY_REQUESTS
                            ) {

                                metrics.recordRateLimitRejected();
                            }
                        }
                );
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}