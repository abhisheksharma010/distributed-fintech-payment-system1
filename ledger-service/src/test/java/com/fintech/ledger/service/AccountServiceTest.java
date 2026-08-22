package com.fintech.ledger.service;

import com.fintech.ledger.dto.CreateAccountRequest;
import com.fintech.ledger.entity.Account;
import com.fintech.ledger.entity.AccountStatus;
import com.fintech.ledger.exception.AccountAlreadyExistsException;
import com.fintech.ledger.exception.AccountNotFoundException;
import com.fintech.ledger.exception.UnsupportedCurrencyException;
import com.fintech.ledger.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {

        accountService =
                new AccountService(
                        accountRepository
                );
    }

    @Test
    void authenticatedUserCreatesZeroBalanceActiveAccount() {

        UUID userId =
                UUID.randomUUID();

        when(
                accountRepository
                        .existsByOwnerIdAndCurrency(
                                userId,
                                "INR"
                        )
        ).thenReturn(false);

        when(
                accountRepository
                        .saveAndFlush(
                                any(Account.class)
                        )
        ).thenAnswer(
                invocation -> {

                    Account account =
                            invocation.getArgument(0);

                    account.setCreatedAt(
                            LocalDateTime.now()
                    );

                    account.setUpdatedAt(
                            LocalDateTime.now()
                    );

                    return account;
                }
        );

        var response =
                accountService.createAccount(
                        userId,
                        new CreateAccountRequest(
                                "inr"
                        )
                );

        ArgumentCaptor<Account> captor =
                ArgumentCaptor.forClass(
                        Account.class
                );

        verify(accountRepository)
                .saveAndFlush(
                        captor.capture()
                );

        Account saved =
                captor.getValue();

        assertEquals(
                userId,
                saved.getOwnerId()
        );

        assertEquals(
                "INR",
                saved.getCurrency()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                saved.getStatus()
        );

        assertEquals(
                0,
                new BigDecimal("0.00")
                        .compareTo(
                                saved.getBalance()
                        )
        );

        assertEquals(
                userId,
                response.ownerId()
        );

        assertEquals(
                0,
                new BigDecimal("0.00")
                        .compareTo(
                                response.balance()
                        )
        );
    }

    @Test
    void duplicateAccountIsRejectedBeforeInsert() {

        UUID userId =
                UUID.randomUUID();

        when(
                accountRepository
                        .existsByOwnerIdAndCurrency(
                                userId,
                                "INR"
                        )
        ).thenReturn(true);

        assertThrows(
                AccountAlreadyExistsException.class,
                () ->
                        accountService
                                .createAccount(
                                        userId,
                                        new CreateAccountRequest(
                                                "INR"
                                        )
                                )
        );

        verify(
                accountRepository,
                never()
        ).saveAndFlush(any());
    }

    @Test
    void databaseUniqueRaceBecomesAccountAlreadyExists() {

        UUID userId =
                UUID.randomUUID();

        when(
                accountRepository
                        .existsByOwnerIdAndCurrency(
                                userId,
                                "INR"
                        )
        ).thenReturn(false);

        when(
                accountRepository
                        .saveAndFlush(
                                any(Account.class)
                        )
        ).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate account"
                )
        );

        assertThrows(
                AccountAlreadyExistsException.class,
                () ->
                        accountService
                                .createAccount(
                                        userId,
                                        new CreateAccountRequest(
                                                "INR"
                                        )
                                )
        );
    }

    @Test
    void unsupportedCurrencyIsRejected() {

        assertThrows(
                UnsupportedCurrencyException.class,
                () ->
                        accountService
                                .createAccount(
                                        UUID.randomUUID(),
                                        new CreateAccountRequest(
                                                "USD"
                                        )
                                )
        );

        verifyNoInteractions(
                accountRepository
        );
    }

    @Test
    void getMyAccountReturnsAuthoritativePersistedAccount() {

        UUID userId =
                UUID.randomUUID();

        UUID accountId =
                UUID.randomUUID();

        Account account =
                Account.builder()
                        .id(accountId)
                        .ownerId(userId)
                        .accountType("WALLET")
                        .balance(
                                new BigDecimal(
                                        "1250.00"
                                )
                        )
                        .currency("INR")
                        .status(
                                AccountStatus.ACTIVE
                        )
                        .createdAt(
                                LocalDateTime.now()
                        )
                        .updatedAt(
                                LocalDateTime.now()
                        )
                        .build();

        when(
                accountRepository
                        .findByOwnerIdAndCurrency(
                                userId,
                                "INR"
                        )
        ).thenReturn(
                Optional.of(account)
        );

        var response =
                accountService
                        .getMyAccount(
                                userId,
                                "INR"
                        );

        assertEquals(
                accountId,
                response.accountId()
        );

        assertEquals(
                userId,
                response.ownerId()
        );

        assertEquals(
                0,
                new BigDecimal("1250.00")
                        .compareTo(
                                response.balance()
                        )
        );
    }

    @Test
    void missingAccountReturnsAccountNotFound() {

        UUID userId =
                UUID.randomUUID();

        when(
                accountRepository
                        .findByOwnerIdAndCurrency(
                                userId,
                                "INR"
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThrows(
                AccountNotFoundException.class,
                () ->
                        accountService
                                .getMyAccount(
                                        userId,
                                        "INR"
                                )
        );
    }
}