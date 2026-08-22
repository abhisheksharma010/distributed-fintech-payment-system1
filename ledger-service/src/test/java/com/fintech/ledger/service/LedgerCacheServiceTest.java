package com.fintech.ledger.service;

import com.fintech.ledger.config.LedgerCacheNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerCacheServiceTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache allCache;

    @Mock
    private Cache userCache;

    @Mock
    private LedgerCacheMetrics metrics;

    private LedgerCacheService cacheService;

    @BeforeEach
    void setUp() {

        cacheService =
                new LedgerCacheService(
                        cacheManager,
                        metrics
                );
    }

    @Test
    void successfulTransferEvictsGlobalSenderAndReceiverCaches() {

        UUID senderId =
                UUID.randomUUID();

        UUID receiverId =
                UUID.randomUUID();

        when(
                cacheManager.getCache(
                        LedgerCacheNames.ALL_ENTRIES
                )
        ).thenReturn(allCache);

        when(
                cacheManager.getCache(
                        LedgerCacheNames.USER_ENTRIES
                )
        ).thenReturn(userCache);

        cacheService.evictAfterTransfer(
                senderId,
                receiverId
        );

        verify(allCache)
                .evict("all");

        verify(userCache)
                .evict(senderId);

        verify(userCache)
                .evict(receiverId);

        verify(
                metrics,
                times(3)
        ).recordEviction();
    }

    @Test
    void redisEvictionFailureNeverEscapesToFinancialFlow() {

        UUID senderId =
                UUID.randomUUID();

        UUID receiverId =
                UUID.randomUUID();

        when(
                cacheManager.getCache(
                        LedgerCacheNames.ALL_ENTRIES
                )
        ).thenReturn(allCache);

        when(
                cacheManager.getCache(
                        LedgerCacheNames.USER_ENTRIES
                )
        ).thenReturn(userCache);

        doThrow(
                new RuntimeException(
                        "Redis unavailable"
                )
        ).when(allCache)
                .evict("all");

        assertDoesNotThrow(
                () ->
                        cacheService
                                .evictAfterTransfer(
                                        senderId,
                                        receiverId
                                )
        );

        verify(metrics)
                .recordRedisFailure();

        /*
         * Even though global eviction failed,
         * sender and receiver cache eviction
         * is still attempted.
         */
        verify(userCache)
                .evict(senderId);

        verify(userCache)
                .evict(receiverId);
    }
}