package com.fintech.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

@Configuration
@EnableCaching
public class LedgerCacheConfig
        implements CachingConfigurer {

    private final LedgerCacheErrorHandler
            cacheErrorHandler;

    public LedgerCacheConfig(
            LedgerCacheErrorHandler cacheErrorHandler
    ) {
        this.cacheErrorHandler =
                cacheErrorHandler;
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer
    ledgerCacheManagerCustomizer(
            @Value(
                    "${ledger.cache.ttl:60s}"
            )
            Duration ttl
    ) {

        RedisCacheConfiguration configuration =
                RedisCacheConfiguration
                        .defaultCacheConfig()
                        .entryTtl(ttl)
                        .disableCachingNullValues();

        return builder ->
                builder
                        .withCacheConfiguration(
                                LedgerCacheNames
                                        .ALL_ENTRIES,
                                configuration
                        )
                        .withCacheConfiguration(
                                LedgerCacheNames
                                        .USER_ENTRIES,
                                configuration
                        );
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return cacheErrorHandler;
    }
}