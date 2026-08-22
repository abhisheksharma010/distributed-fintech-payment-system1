package com.fintech.payment.controller;

import com.fintech.payment.dto.CreatePaymentRequest;
import com.fintech.payment.dto.PaymentPageResponse;
import com.fintech.payment.dto.PaymentResponse;
import com.fintech.payment.exception.AuthenticatedUserRequiredException;
import com.fintech.payment.exception.SenderIdentityMismatchException;
import com.fintech.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/pay")
    public PaymentResponse createPayment(

            @RequestHeader(
                    value = "X-USER-ID",
                    required = false
            )
            String authenticatedUserId,

            @Valid
            @RequestBody
            CreatePaymentRequest request
    ) {

        UUID userId =
                parseAuthenticatedUserId(
                        authenticatedUserId
                );

        if (
                !userId.equals(
                        request.getSenderId()
                )
        ) {

            throw new SenderIdentityMismatchException();
        }

        return paymentService
                .createPayment(
                        request
                );
    }

    @GetMapping("/mine")
    public PaymentPageResponse getMyPayments(

            @RequestHeader(
                    value = "X-USER-ID",
                    required = false
            )
            String authenticatedUserId,

            @RequestParam(
                    defaultValue = "0"
            )
            int page,

            @RequestParam(
                    defaultValue = "10"
            )
            int size
    ) {

        UUID userId =
                parseAuthenticatedUserId(
                        authenticatedUserId
                );

        return paymentService
                .getMyPayments(
                        userId,
                        page,
                        size
                );
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(

            @PathVariable
            UUID paymentId,

            @RequestHeader(
                    value = "X-USER-ID",
                    required = false
            )
            String authenticatedUserId,

            @RequestHeader(
                    value = "X-USER-ROLES",
                    required = false
            )
            String roles
    ) {

        UUID userId =
                parseAuthenticatedUserId(
                        authenticatedUserId
                );

        return paymentService
                .getPayment(
                        paymentId,
                        userId,
                        parseRoles(roles)
                );
    }

    private UUID parseAuthenticatedUserId(
            String authenticatedUserId
    ) {

        if (
                authenticatedUserId == null
                        ||
                        authenticatedUserId
                                .isBlank()
        ) {

            throw new AuthenticatedUserRequiredException(
                    "Authenticated user context is required"
            );
        }

        try {

            return UUID.fromString(
                    authenticatedUserId
            );

        } catch (
                IllegalArgumentException ex
        ) {

            throw new AuthenticatedUserRequiredException(
                    "Authenticated user context is malformed"
            );
        }
    }

    private Set<String> parseRoles(
            String roles
    ) {

        if (
                roles == null
                        ||
                        roles.isBlank()
        ) {

            return Set.of();
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