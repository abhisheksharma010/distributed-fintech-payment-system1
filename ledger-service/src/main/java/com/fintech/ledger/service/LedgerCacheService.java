package com.fintech.ledger.service;

import com.fintech.ledger.config.LedgerCacheNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerCacheService {

    private static final String ALL_KEY =
            "all";

    private final CacheManager cacheManager;
    private final LedgerCacheMetrics metrics;

    public void evictAfterTransfer(
            UUID senderId,
            UUID receiverId
    ) {

        evict(
                LedgerCacheNames.ALL_ENTRIES,
                ALL_KEY
        );

        evict(
                LedgerCacheNames.USER_ENTRIES,
                senderId
        );

        evict(
                LedgerCacheNames.USER_ENTRIES,
                receiverId
        );
    }

    private void evict(
            String cacheName,
            Object key
    ) {

        try {

            Cache cache =
                    cacheManager.getCache(
                            cacheName
                    );

            if (cache == null) {

                log.warn(
                        "Ledger cache unavailable cache={} action=EVICT_SKIPPED",
                        cacheName
                );

                return;
            }

            cache.evict(key);

            metrics.recordEviction();

            log.info(
                    "Ledger cache evicted cache={} action=EVICT",
                    cacheName
            );

        } catch (
                RuntimeException ex
        ) {

            /*
             * Cache invalidation failure must never
             * affect an already committed financial
             * transaction.
             */
            metrics.recordRedisFailure();

            log.warn(
                    "Ledger cache eviction failed cache={} action=IGNORE_REDIS_FAILURE failure={}",
                    cacheName,
                    ex.getMessage()
            );
        }
    }
}