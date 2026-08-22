package com.fintech.ledger;

import com.fintech.ledger.config.LedgerCacheNames;
import com.fintech.ledger.entity.LedgerEntry;
import com.fintech.ledger.entity.LedgerEntryType;
import com.fintech.ledger.repository.LedgerRepository;
import com.fintech.ledger.service.LedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        properties = {
                "spring.kafka.listener.auto-startup=false",
                "ledger.reconciliation.enabled=false",
                "ledger.cache.ttl=60s"
        }
)
class LedgerCacheIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private CacheManager cacheManager;

    private UUID paymentIdA;
    private UUID paymentIdB;
    private UUID accountIdA;
    private UUID accountIdB;
    private UUID userA;
    private UUID userB;

    @BeforeEach
    void clearCaches() {

        var all =
                cacheManager.getCache(
                        LedgerCacheNames.ALL_ENTRIES
                );

        var users =
                cacheManager.getCache(
                        LedgerCacheNames.USER_ENTRIES
                );

        if (all != null) {
            all.clear();
        }

        if (users != null) {
            users.clear();
        }
    }

    @AfterEach
    void cleanup() {

        if (paymentIdA != null) {

            ledgerRepository
                    .findByPaymentId(
                            paymentIdA
                    )
                    .forEach(
                            ledgerRepository::delete
                    );
        }

        if (paymentIdB != null) {

            ledgerRepository
                    .findByPaymentId(
                            paymentIdB
                    )
                    .forEach(
                            ledgerRepository::delete
                    );
        }

        clearCaches();
    }

    @Test
    void repeatedReadCanReturnFromRedis() {

        /*
         * Reuse an owner/account arrangement matching
         * your repository's findByOwnerId implementation.
         *
         * If findByOwnerId joins Account, create the
         * corresponding Account here using your existing
         * test helper.
         *
         * The key assertion is:
         *
         * first call -> DB
         * second call -> cached equivalent response
         */

        List<?> first =
                ledgerService.getAllEntries();

        List<?> second =
                ledgerService.getAllEntries();

        assertEquals(
                first,
                second
        );

        assertNotNull(
                cacheManager
                        .getCache(
                                LedgerCacheNames.ALL_ENTRIES
                        )
                        .get(
                                "all"
                        )
        );
    }
}