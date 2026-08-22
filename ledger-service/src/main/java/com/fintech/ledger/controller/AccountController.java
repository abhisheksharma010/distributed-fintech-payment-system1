package com.fintech.ledger.controller;

import com.fintech.ledger.dto.AccountResponse;
import com.fintech.ledger.dto.CreateAccountRequest;
import com.fintech.ledger.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private static final String DEFAULT_CURRENCY =
            "INR";

    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(

            @RequestHeader(
                    value = "X-USER-ID",
                    required = false
            )
            String userId,

            @Valid
            @RequestBody
            CreateAccountRequest request
    ) {

        return accountService.createAccount(
                requireUserId(userId),
                request
        );
    }

    @GetMapping("/me")
    public AccountResponse getMyAccount(

            @RequestHeader(
                    value = "X-USER-ID",
                    required = false
            )
            String userId,

            @RequestParam(
                    value = "currency",
                    defaultValue = DEFAULT_CURRENCY
            )
            String currency
    ) {

        return accountService.getMyAccount(
                requireUserId(userId),
                currency
        );
    }

    private UUID requireUserId(
            String userId
    ) {

        if (
                userId == null
                        ||
                        userId.isBlank()
        ) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authenticated user context is required"
            );
        }

        try {

            return UUID.fromString(
                    userId
            );

        } catch (
                IllegalArgumentException ex
        ) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authenticated user context is malformed"
            );
        }
    }
}