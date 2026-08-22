package com.fintech.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "payment.recovery.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class StuckPaymentRecoveryJob {

    private final StuckPaymentRecoveryService
            recoveryService;

    @Scheduled(
            fixedDelayString =
                    "${payment.recovery.poll-interval-ms:30000}"
    )
    public void recover() {

        try {

            int recovered =
                    recoveryService
                            .recoverStuckPayments();

            if (recovered > 0) {

                log.info(
                        "Stuck payment recovery scan processed count={}",
                        recovered
                );
            }

        } catch (Exception ex) {

            /*
             * Do not kill future scheduled executions because
             * one recovery scan failed.
             */
            log.error(
                    "Stuck payment recovery scan failed",
                    ex
            );
        }
    }
}