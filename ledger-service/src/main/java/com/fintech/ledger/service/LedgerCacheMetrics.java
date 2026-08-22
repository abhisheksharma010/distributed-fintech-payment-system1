package com.fintech.ledger.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class LedgerCacheMetrics {

    private final Counter miss;
    private final Counter eviction;
    private final Counter redisFailure;

    public LedgerCacheMetrics(
            MeterRegistry registry
    ) {

        this.miss =
                Counter.builder(
                                "ledger.cache.miss"
                        )
                        .description(
                                "Ledger cache misses requiring database reads"
                        )
                        .register(registry);

        this.eviction =
                Counter.builder(
                                "ledger.cache.eviction"
                        )
                        .description(
                                "Ledger cache evictions"
                        )
                        .register(registry);

        this.redisFailure =
                Counter.builder(
                                "ledger.cache.redis.failure"
                        )
                        .description(
                                "Redis failures encountered by Ledger cache"
                        )
                        .register(registry);
    }

    public void recordMiss() {
        miss.increment();
    }

    public void recordEviction() {
        eviction.increment();
    }

    public void recordRedisFailure() {
        redisFailure.increment();
    }
}