package com.fintech.ledger.controller;

import com.fintech.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import com.fintech.ledger.dto.LedgerEntryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
@Slf4j
public class LedgerController {

    private final LedgerService ledgerService;

    @GetMapping("/entries")
    public List<LedgerEntryResponse> getAllEntries(            @RequestHeader(
                    value = "X-USER-ID",
                    required = false
            )
            String userId,

            @RequestHeader(
                    value = "X-USER-ROLES",
                    required = false
            )
            String roles
    ) {

        UUID authenticatedUserId =
                requireUserId(
                        userId
                );

        Set<String> authenticatedRoles =
                requireRoles(
                        roles
                );

        if (
                !authenticatedRoles
                        .contains(
                                "ADMIN"
                        )
        ) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Admin access required"
            );
        }

        log.info(
                "Admin fetching all ledger entries userId={}",
                authenticatedUserId
        );

        return ledgerService
                .getAllEntries();
    }

    @GetMapping("/my-entries")
    public List<LedgerEntryResponse> getMyEntries(
            @RequestHeader(
                    value = "X-USER-ID",
                    required = false
            )
            String userId
    ) {

        UUID authenticatedUserId =
                requireUserId(
                        userId
                );

        log.info(
                "Fetching ledger entries for userId={}",
                authenticatedUserId
        );

        return ledgerService
                .getEntriesForUser(
                        authenticatedUserId
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

    private Set<String> requireRoles(
            String roles
    ) {

        if (
                roles == null
                        ||
                        roles.isBlank()
        ) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Authenticated role context is required"
            );
        }

        return Arrays.stream(
                        roles.split(",")
                )
                .map(
                        String::trim
                )
                .filter(
                        role ->
                                !role.isEmpty()
                )
                .collect(
                        Collectors
                                .toUnmodifiableSet()
                );
    }
}