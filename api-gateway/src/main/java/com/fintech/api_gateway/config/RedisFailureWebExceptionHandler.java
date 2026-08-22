package com.fintech.api_gateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class RedisFailureWebExceptionHandler
        implements WebExceptionHandler {

    private final GatewayRedisMetrics metrics;

    @Override
    public Mono<Void> handle(
            ServerWebExchange exchange,
            Throwable ex
    ) {

        if (!isRedisFailure(ex)) {

            return Mono.error(ex);
        }

        metrics.recordRedisFailure();

        log.error(
                "Gateway Redis unavailable component=rateLimiter action=FAIL_CLOSED path={}",
                exchange
                        .getRequest()
                        .getURI()
                        .getPath(),
                ex
        );

        exchange
                .getResponse()
                .setStatusCode(
                        HttpStatus.SERVICE_UNAVAILABLE
                );

        return exchange
                .getResponse()
                .setComplete();
    }

    private boolean isRedisFailure(
            Throwable throwable
    ) {

        Throwable current =
                throwable;

        while (current != null) {

            if (
                    current
                            instanceof
                            RedisConnectionFailureException
                            ||
                            current
                                    instanceof
                                    RedisSystemException
            ) {

                return true;
            }

            current =
                    current.getCause();
        }

        return false;
    }
}