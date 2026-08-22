package com.fintech.api_gateway.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    @Bean
    public Counter rateLimitIdentityFallbackCounter(
            MeterRegistry meterRegistry
    ) {

        return Counter.builder(
                        "gateway.rate_limit.identity_fallback"
                )
                .description(
                        "Rate limit requests using fallback identity"
                )
                .register(meterRegistry);
    }

    @Bean
    public KeyResolver userKeyResolver(
            Counter rateLimitIdentityFallbackCounter
    ) {

        return exchange ->
                ReactiveSecurityContextHolder
                        .getContext()
                        .map(
                                context ->
                                        context.getAuthentication()
                        )
                        .filter(
                                Authentication::isAuthenticated
                        )
                        .map(
                                Authentication::getName
                        )
                        .filter(
                                name ->
                                        name != null
                                                &&
                                                !name.isBlank()
                        )
                        .switchIfEmpty(
                                Mono.fromSupplier(
                                        () -> {

                                            rateLimitIdentityFallbackCounter
                                                    .increment();

                                            return fallbackKey(
                                                    exchange
                                            );
                                        }
                                )
                        );
    }

    private String fallbackKey(
            ServerWebExchange exchange
    ) {

        if (
                exchange
                        .getRequest()
                        .getRemoteAddress()
                        != null
                        &&
                        exchange
                                .getRequest()
                                .getRemoteAddress()
                                .getAddress()
                                != null
        ) {

            return "ip:"
                    +
                    exchange
                            .getRequest()
                            .getRemoteAddress()
                            .getAddress()
                            .getHostAddress();
        }

        return "anonymous";
    }
}