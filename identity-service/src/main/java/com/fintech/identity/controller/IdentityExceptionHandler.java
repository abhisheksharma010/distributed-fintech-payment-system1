package com.fintech.identity.controller;

import com.fintech.identity.dto.IdentityErrorResponse;
import com.fintech.identity.exception.EmailAlreadyRegisteredException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class IdentityExceptionHandler {



    @ExceptionHandler(
            MethodArgumentNotValidException.class
    )
    public ResponseEntity<IdentityErrorResponse>
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
            BadCredentialsException.class
    )
    public ResponseEntity<IdentityErrorResponse>
    handleBadCredentials(
            BadCredentialsException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Invalid email or password",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(
            IllegalArgumentException.class
    )
    public ResponseEntity<IdentityErrorResponse>
    handleIllegalArgument(
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

    private ResponseEntity<IdentityErrorResponse>
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
                        new IdentityErrorResponse(
                                code,
                                message,
                                Instant.now(),
                                request.getRequestURI(),
                                fieldErrors
                        )
                );
    }
    @ExceptionHandler(
            EmailAlreadyRegisteredException.class
    )
    public ResponseEntity<IdentityErrorResponse>
    handleEmailAlreadyRegistered(
            EmailAlreadyRegisteredException ex,
            HttpServletRequest request
    ) {

        return error(
                HttpStatus.CONFLICT,
                "EMAIL_ALREADY_REGISTERED",
                ex.getMessage(),
                request,
                Map.of()
        );
    }
}