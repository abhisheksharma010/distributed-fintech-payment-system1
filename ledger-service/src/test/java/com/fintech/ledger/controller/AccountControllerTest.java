package com.fintech.ledger.controller;

import com.fintech.ledger.dto.AccountResponse;
import com.fintech.ledger.dto.CreateAccountRequest;
import com.fintech.ledger.entity.AccountStatus;
import com.fintech.ledger.exception.AccountAlreadyExistsException;
import com.fintech.ledger.exception.AccountNotFoundException;
import com.fintech.ledger.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountService accountService;

    @Test
    void authenticatedUserCanCreateOwnAccount()
            throws Exception {

        UUID userId =
                UUID.randomUUID();

        UUID accountId =
                UUID.randomUUID();

        when(
                accountService.createAccount(
                        eq(userId),
                        eq(
                                new CreateAccountRequest(
                                        "INR"
                                )
                        )
                )
        ).thenReturn(
                response(
                        accountId,
                        userId
                )
        );

        mockMvc.perform(
                        post(
                                "/api/accounts"
                        )
                                .header(
                                        "X-USER-ID",
                                        userId.toString()
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "currency": "INR"
                                        }
                                        """
                                )
                )
                .andExpect(
                        status().isCreated()
                )
                .andExpect(
                        jsonPath("$.ownerId")
                                .value(
                                        userId.toString()
                                )
                )
                .andExpect(
                        jsonPath("$.balance")
                                .value(0.00)
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("ACTIVE")
                );
    }

    @Test
    void missingAuthenticatedIdentityReturnsUnauthorized()
            throws Exception {

        mockMvc.perform(
                        post(
                                "/api/accounts"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {"currency":"INR"}
                                        """
                                )
                )
                .andExpect(
                        status()
                                .isUnauthorized()
                );

        verifyNoInteractions(
                accountService
        );
    }

    @Test
    void malformedAuthenticatedIdentityReturnsUnauthorized()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/api/accounts/me"
                        )
                                .header(
                                        "X-USER-ID",
                                        "not-a-uuid"
                                )
                )
                .andExpect(
                        status()
                                .isUnauthorized()
                );

        verifyNoInteractions(
                accountService
        );
    }

    @Test
    void duplicateAccountReturnsConflict()
            throws Exception {

        UUID userId =
                UUID.randomUUID();

        when(
                accountService.createAccount(
                        eq(userId),
                        eq(
                                new CreateAccountRequest(
                                        "INR"
                                )
                        )
                )
        ).thenThrow(
                new AccountAlreadyExistsException(
                        "INR"
                )
        );

        mockMvc.perform(
                        post(
                                "/api/accounts"
                        )
                                .header(
                                        "X-USER-ID",
                                        userId.toString()
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {"currency":"INR"}
                                        """
                                )
                )
                .andExpect(
                        status().isConflict()
                )
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "ACCOUNT_ALREADY_EXISTS"
                                )
                );
    }

    @Test
    void getMyAccountUsesAuthenticatedUser()
            throws Exception {

        UUID userId =
                UUID.randomUUID();

        UUID accountId =
                UUID.randomUUID();

        when(
                accountService
                        .getMyAccount(
                                userId,
                                "INR"
                        )
        ).thenReturn(
                response(
                        accountId,
                        userId
                )
        );

        mockMvc.perform(
                        get(
                                "/api/accounts/me"
                        )
                                .header(
                                        "X-USER-ID",
                                        userId.toString()
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.ownerId")
                                .value(
                                        userId.toString()
                                )
                );
    }

    @Test
    void missingAccountReturnsStructuredNotFound()
            throws Exception {

        UUID userId =
                UUID.randomUUID();

        when(
                accountService
                        .getMyAccount(
                                userId,
                                "INR"
                        )
        ).thenThrow(
                new AccountNotFoundException(
                        "INR"
                )
        );

        mockMvc.perform(
                        get(
                                "/api/accounts/me"
                        )
                                .header(
                                        "X-USER-ID",
                                        userId.toString()
                                )
                )
                .andExpect(
                        status().isNotFound()
                )
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "ACCOUNT_NOT_FOUND"
                                )
                );
    }

    @Test
    void blankCurrencyReturnsBadRequest()
            throws Exception {

        mockMvc.perform(
                        post(
                                "/api/accounts"
                        )
                                .header(
                                        "X-USER-ID",
                                        UUID.randomUUID()
                                                .toString()
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {"currency":""}
                                        """
                                )
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "VALIDATION_ERROR"
                                )
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Request validation failed"
                                )
                )
                .andExpect(
                        jsonPath("$.fieldErrors.currency")
                                .exists()
                );
    }

    private static AccountResponse response(
            UUID accountId,
            UUID ownerId
    ) {

        LocalDateTime now =
                LocalDateTime.now();

        return new AccountResponse(
                accountId,
                ownerId,
                new BigDecimal("0.00"),
                "INR",
                AccountStatus.ACTIVE,
                now,
                now
        );
    }
}