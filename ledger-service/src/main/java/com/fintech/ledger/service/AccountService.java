package com.fintech.ledger.service;

import com.fintech.ledger.dto.AccountResponse;
import com.fintech.ledger.dto.CreateAccountRequest;
import com.fintech.ledger.entity.Account;
import com.fintech.ledger.entity.AccountStatus;
import com.fintech.ledger.exception.AccountAlreadyExistsException;
import com.fintech.ledger.exception.AccountNotFoundException;
import com.fintech.ledger.exception.UnsupportedCurrencyException;
import com.fintech.ledger.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    /*
     * Current Ledger/payment implementation supports INR only.
     *
     * Story 9 does not introduce FX or multi-currency
     * financial processing.
     */
    private static final String SUPPORTED_CURRENCY =
            "INR";

    private static final String ACCOUNT_TYPE =
            "WALLET";

    private final AccountRepository accountRepository;

    @Transactional
    public AccountResponse createAccount(
            UUID authenticatedUserId,
            CreateAccountRequest request
    ) {

        String currency =
                normalizeAndValidateCurrency(
                        request.currency()
                );

        if (
                accountRepository
                        .existsByOwnerIdAndCurrency(
                                authenticatedUserId,
                                currency
                        )
        ) {

            log.info(
                    "Duplicate account creation rejected userId={} currency={}",
                    authenticatedUserId,
                    currency
            );

            throw new AccountAlreadyExistsException(
                    currency
            );
        }

        Account account =
                Account.builder()
                        .id(UUID.randomUUID())
                        .ownerId(
                                authenticatedUserId
                        )
                        .accountType(
                                ACCOUNT_TYPE
                        )
                        .balance(
                                BigDecimal.ZERO
                                        .setScale(2)
                        ).openingBalance(
                                BigDecimal.ZERO
                                        .setScale(2)
                        )
                        .currency(currency)
                        .status(
                                AccountStatus.ACTIVE
                        )
                        .build();

        try {

            Account saved =
                    accountRepository
                            .saveAndFlush(
                                    account
                            );

            log.info(
                    "Account created accountId={} userId={} currency={}",
                    saved.getId(),
                    authenticatedUserId,
                    currency
            );

            return toResponse(saved);

        } catch (
                DataIntegrityViolationException ex
        ) {

            /*
             * Two concurrent requests may both pass the
             * existence check.
             *
             * UNIQUE(owner_id, currency) is the final
             * protection.
             */
            throw new AccountAlreadyExistsException(
                    currency
            );
        }
    }

    @Transactional(readOnly = true)
    public AccountResponse getMyAccount(
            UUID authenticatedUserId,
            String requestedCurrency
    ) {

        String currency =
                normalizeAndValidateCurrency(
                        requestedCurrency
                );

        Account account =
                accountRepository
                        .findByOwnerIdAndCurrency(
                                authenticatedUserId,
                                currency
                        )
                        .orElseThrow(
                                () -> {

                                    log.info(
                                            "Account lookup failed userId={} currency={}",
                                            authenticatedUserId,
                                            currency
                                    );

                                    return new AccountNotFoundException(
                                            currency
                                    );
                                }
                        );

        return toResponse(account);
    }

    private String normalizeAndValidateCurrency(
            String currency
    ) {

        if (
                currency == null
                        ||
                        currency.isBlank()
        ) {

            throw new UnsupportedCurrencyException(
                    currency
            );
        }

        String normalized =
                currency
                        .trim()
                        .toUpperCase(
                                Locale.ROOT
                        );

        if (
                !SUPPORTED_CURRENCY
                        .equals(normalized)
        ) {

            throw new UnsupportedCurrencyException(
                    normalized
            );
        }

        return normalized;
    }

    private AccountResponse toResponse(
            Account account
    ) {

        return new AccountResponse(
                account.getId(),
                account.getOwnerId(),
                account.getBalance(),
                account.getCurrency(),
                account.getStatus(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}