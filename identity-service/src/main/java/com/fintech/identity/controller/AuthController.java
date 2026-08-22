package com.fintech.identity.controller;

import com.fintech.identity.dto.AuthResponse;
import com.fintech.identity.dto.LoginRequest;
import com.fintech.identity.dto.RegisterRequest;
import com.fintech.identity.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public AuthResponse register(
            @Valid
            @RequestBody
            RegisterRequest request
    ) {

        authService.register(request);

        return new AuthResponse(
                "User registered successfully"
        );
    }

    @PostMapping("/login")
    public AuthResponse login(
            @Valid
            @RequestBody
            LoginRequest request
    ) {

        String token =
                authService.login(request);

        return new AuthResponse(token);
    }
}