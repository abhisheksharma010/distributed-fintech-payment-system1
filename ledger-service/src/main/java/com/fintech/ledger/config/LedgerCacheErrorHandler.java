package com.fintech.ledger.config;

import com.fintech.ledger.service.LedgerCacheMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerCacheErrorHandler
        implements CacheErrorHandler {

    private final LedgerCacheMetrics metrics;

    @Override
    public void handleCacheGetError(
            RuntimeException exception,
            Cache cache,
            Object key
    ) {

        recordFailure(
                "GET",
                cache,
                exception
        );
    }

    @Override
    public void handleCachePutError(
            RuntimeException exception,
            Cache cache,
            Object key,
            Object value
    ) {

        recordFailure(
                "PUT",
                cache,
                exception
        );
    }

    @Override
    public void handleCacheEvictError(
            RuntimeException exception,
            Cache cache,
            Object key
    ) {

        recordFailure(
                "EVICT",
                cache,
                exception
        );
    }

    @Override
    public void handleCacheClearError(
            RuntimeException exception,
            Cache cache
    ) {

        recordFailure(
                "CLEAR",
                cache,
                exception
        );
    }

    private void recordFailure(
            String operation,
            Cache cache,
            RuntimeException exception
    ) {

        metrics.recordRedisFailure();

        log.warn(
                "Ledger cache Redis failure cache={} operation={} action=FALLBACK_TO_DATABASE failure={}",
                cache == null
                        ? null
                        : cache.getName(),
                operation,
                exception.getMessage()
        );
    }
}