package com.fintech.ledger.controller;

import com.fintech.ledger.dto.AccountErrorResponse;
import com.fintech.ledger.exception.AccountAlreadyExistsException;
import com.fintech.ledger.exception.AccountNotFoundException;
import com.fintech.ledger.exception.UnsupportedCurrencyException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class AccountExceptionHandler {

    @ExceptionHandler(
            AccountAlreadyExistsException.class
    )
    public ResponseEntity<AccountErrorResponse>
    handleAlreadyExists(
            AccountAlreadyExistsException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.CONFLICT,
                "ACCOUNT_ALREADY_EXISTS",
                ex.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(
            AccountNotFoundException.class
    )
    public ResponseEntity<AccountErrorResponse>
    handleNotFound(
            AccountNotFoundException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.NOT_FOUND,
                "ACCOUNT_NOT_FOUND",
                ex.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(
            UnsupportedCurrencyException.class
    )
    public ResponseEntity<AccountErrorResponse>
    handleUnsupportedCurrency(
            UnsupportedCurrencyException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_CURRENCY",
                ex.getMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(
            MethodArgumentNotValidException.class
    )
    public ResponseEntity<AccountErrorResponse>
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

    private ResponseEntity<AccountErrorResponse>
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
                        new AccountErrorResponse(
                                code,
                                message,
                                Instant.now(),
                                request.getRequestURI(),
                                fieldErrors
                        )
                );
    }
}