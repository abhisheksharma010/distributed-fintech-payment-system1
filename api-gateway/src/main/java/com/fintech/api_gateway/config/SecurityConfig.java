package com.fintech.api_gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain
    securityWebFilterChain(
            ServerHttpSecurity http
    ) {

        return http
                .csrf(
                        ServerHttpSecurity
                                .CsrfSpec::disable
                )
                .authorizeExchange(
                        exchange ->
                                exchange
                                        .pathMatchers(
                                                "/api/auth/register",
                                                "/api/auth/login"
                                        )
                                        .permitAll()

                                        .pathMatchers(
                                                "/api/ledger/entries"
                                        )
                                        .hasRole(
                                                "ADMIN"
                                        )

                                        .anyExchange()
                                        .authenticated()
                )
                .build();
    }
}