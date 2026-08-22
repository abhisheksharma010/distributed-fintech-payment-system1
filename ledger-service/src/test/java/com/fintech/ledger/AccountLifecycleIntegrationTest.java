package com.fintech.ledger;

import com.fintech.ledger.dto.CreateAccountRequest;
import com.fintech.ledger.entity.AccountStatus;
import com.fintech.ledger.exception.AccountAlreadyExistsException;
import com.fintech.ledger.repository.AccountRepository;
import com.fintech.ledger.service.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        properties = {
                "spring.kafka.listener.auto-startup=false"
        }
)
class AccountLifecycleIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    private UUID testOwnerId;

    @AfterEach
    void cleanup() {

        if (testOwnerId != null) {

            accountRepository
                    .findByOwnerIdAndCurrency(
                            testOwnerId,
                            "INR"
                    )
                    .ifPresent(
                            accountRepository::delete
                    );
        }
    }

    @Test
    void createsAndRetrievesAccountFromCleanState() {

        testOwnerId =
                UUID.randomUUID();

        assertTrue(
                accountRepository
                        .findByOwnerIdAndCurrency(
                                testOwnerId,
                                "INR"
                        )
                        .isEmpty()
        );

        var created =
                accountService
                        .createAccount(
                                testOwnerId,
                                new CreateAccountRequest(
                                        "INR"
                                )
                        );

        assertEquals(
                testOwnerId,
                created.ownerId()
        );

        assertEquals(
                "INR",
                created.currency()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                created.status()
        );

        assertEquals(
                0,
                new BigDecimal("0.00")
                        .compareTo(
                                created.balance()
                        )
        );

        var retrieved =
                accountService
                        .getMyAccount(
                                testOwnerId,
                                "INR"
                        );

        assertEquals(
                created.accountId(),
                retrieved.accountId()
        );
    }

    @Test
    void concurrentDuplicateCreationLeavesExactlyOneAccount()
            throws Exception {

        testOwnerId =
                UUID.randomUUID();

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            List<Callable<Boolean>> tasks =
                    List.of(
                            () ->
                                    createAndReportSuccess(
                                            testOwnerId
                                    ),
                            () ->
                                    createAndReportSuccess(
                                            testOwnerId
                                    )
                    );

            List<Future<Boolean>> futures =
                    executor.invokeAll(tasks);

            long successCount = 0;
            long conflictCount = 0;

            for (
                    Future<Boolean> future :
                    futures
            ) {

                if (future.get()) {
                    successCount++;
                } else {
                    conflictCount++;
                }
            }

            assertEquals(
                    1,
                    successCount
            );

            assertEquals(
                    1,
                    conflictCount
            );

            long matchingAccounts =
                    accountRepository
                            .findAll()
                            .stream()
                            .filter(
                                    account ->
                                            testOwnerId
                                                    .equals(
                                                            account.getOwnerId()
                                                    )
                            )
                            .filter(
                                    account ->
                                            "INR"
                                                    .equals(
                                                            account.getCurrency()
                                                    )
                            )
                            .count();

            assertEquals(
                    1,
                    matchingAccounts
            );

        } finally {

            executor.shutdownNow();
        }
    }

    private boolean createAndReportSuccess(
            UUID ownerId
    ) {

        try {

            accountService.createAccount(
                    ownerId,
                    new CreateAccountRequest(
                            "INR"
                    )
            );

            return true;

        } catch (
                AccountAlreadyExistsException ex
        ) {

            return false;
        }
    }
}