package com.fintech.payment.controller;

import com.fintech.payment.dto.PaymentErrorResponse;
import com.fintech.payment.exception.AuthenticatedUserRequiredException;
import com.fintech.payment.exception.IdempotencyKeyReusedException;
import com.fintech.payment.exception.PaymentAccessDeniedException;
import com.fintech.payment.exception.PaymentNotFoundException;
import com.fintech.payment.exception.SenderIdentityMismatchException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class PaymentExceptionHandler {

    @ExceptionHandler(
            IdempotencyKeyReusedException.class
    )
    public ResponseEntity<PaymentErrorResponse>
    handleIdempotencyKeyReused(
            IdempotencyKeyReusedException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                ex.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(
            PaymentNotFoundException.class
    )
    public ResponseEntity<PaymentErrorResponse>
    handlePaymentNotFound(
            PaymentNotFoundException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.NOT_FOUND,
                "PAYMENT_NOT_FOUND",
                ex.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(
            PaymentAccessDeniedException.class
    )
    public ResponseEntity<PaymentErrorResponse>
    handlePaymentAccessDenied(
            PaymentAccessDeniedException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.FORBIDDEN,
                "PAYMENT_ACCESS_DENIED",
                ex.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(
            AuthenticatedUserRequiredException.class
    )
    public ResponseEntity<PaymentErrorResponse>
    handleAuthenticatedUserRequired(
            AuthenticatedUserRequiredException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                ex.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(
            SenderIdentityMismatchException.class
    )
    public ResponseEntity<PaymentErrorResponse>
    handleSenderIdentityMismatch(
            SenderIdentityMismatchException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.FORBIDDEN,
                "SENDER_IDENTITY_MISMATCH",
                ex.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(
            MethodArgumentNotValidException.class
    )
    public ResponseEntity<PaymentErrorResponse>
    handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {

        Map<String, String> fieldErrors =
                new LinkedHashMap<>();

        ex.getBindingResult()
                .getFieldErrors()
                .forEach(
                        error ->
                                fieldErrors.putIfAbsent(
                                        error.getField(),
                                        error.getDefaultMessage()
                                )
                );

        return error(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(
            MethodArgumentTypeMismatchException.class
    )
    public ResponseEntity<PaymentErrorResponse>
    handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Invalid value for "
                        + ex.getName(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(
            IllegalArgumentException.class
    )
    public ResponseEntity<PaymentErrorResponse>
    handleIllegalArgumentException(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                ex.getMessage(),
                request,
                Map.of()
        );
    }

    private ResponseEntity<PaymentErrorResponse>
    error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> fieldErrors
    ) {

        return ResponseEntity
                .status(status)
                .body(
                        new PaymentErrorResponse(
                                code,
                                message,
                                Instant.now(),
                                request.getRequestURI(),
                                fieldErrors
                        )
                );
    }
}