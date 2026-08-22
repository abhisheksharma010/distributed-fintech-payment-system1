package com.fintech.ledger.config;

import com.fintech.ledger.service.LedgerCacheMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class LedgerCacheErrorHandlerTest {

    private LedgerCacheMetrics metrics;
    private Cache cache;

    private LedgerCacheErrorHandler handler;

    @BeforeEach
    void setUp() {

        metrics =
                mock(
                        LedgerCacheMetrics.class
                );

        cache =
                mock(
                        Cache.class
                );

        handler =
                new LedgerCacheErrorHandler(
                        metrics
                );

        when(
                cache.getName()
        ).thenReturn(
                "ledgerUserEntries"
        );
    }

    @Test
    void cacheGetFailureFallsBackWithoutThrowing() {

        assertDoesNotThrow(
                () ->
                        handler
                                .handleCacheGetError(
                                        new RuntimeException(
                                                "Redis down"
                                        ),
                                        cache,
                                        "key"
                                )
        );

        verify(metrics)
                .recordRedisFailure();
    }

    @Test
    void cachePutFailureDoesNotBreakReadRequest() {

        assertDoesNotThrow(
                () ->
                        handler
                                .handleCachePutError(
                                        new RuntimeException(
                                                "Redis down"
                                        ),
                                        cache,
                                        "key",
                                        "value"
                                )
        );

        verify(metrics)
                .recordRedisFailure();
    }
}