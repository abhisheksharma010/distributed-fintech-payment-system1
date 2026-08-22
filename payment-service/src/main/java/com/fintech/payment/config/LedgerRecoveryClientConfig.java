package com.fintech.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class LedgerRecoveryClientConfig {

    @Bean
    public RestClient ledgerRecoveryRestClient(
            LedgerRecoveryProperties properties
    ) {

        SimpleClientHttpRequestFactory
                requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(
                properties.getConnectTimeoutMs()
        );

        requestFactory.setReadTimeout(
                properties.getReadTimeoutMs()
        );

        return RestClient
                .builder()
                .baseUrl(
                        properties.getBaseUrl()
                )
                .requestFactory(
                        requestFactory
                )
                .build();
    }
}