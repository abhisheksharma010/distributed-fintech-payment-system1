package com.fintech.api_gateway.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class GatewayRedisMetrics {

    private final Counter rateLimitRejected;
    private final Counter redisFailure;

    public GatewayRedisMetrics(
            MeterRegistry registry
    ) {

        this.rateLimitRejected =
                Counter.builder(
                                "gateway.rate_limit.rejected"
                        )
                        .description(
                                "Requests rejected by API rate limiting"
                        )
                        .register(registry);

        this.redisFailure =
                Counter.builder(
                                "gateway.redis.failure"
                        )
                        .description(
                                "Gateway Redis failures"
                        )
                        .register(registry);
    }

    public void recordRateLimitRejected() {
        rateLimitRejected.increment();
    }

    public void recordRedisFailure() {
        redisFailure.increment();
    }
}