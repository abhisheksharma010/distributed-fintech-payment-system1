package com.fintech.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.dto.RegisterRequest;
import com.fintech.identity.exception.EmailAlreadyRegisteredException;
import com.fintech.identity.security.SecurityConfig;
import com.fintech.identity.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @Test
    void duplicateRegistrationReturnsConflict()
            throws Exception {

        RegisterRequest request =
                validRequest();

        doThrow(
                new EmailAlreadyRegisteredException()
        ).when(authService)
                .register(
                        any(RegisterRequest.class)
                );

        mockMvc.perform(
                        post(
                                "/api/auth/register"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        objectMapper
                                                .writeValueAsString(
                                                        request
                                                )
                                )
                )
                .andExpect(
                        status().isConflict()
                )
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "EMAIL_ALREADY_REGISTERED"
                                )
                )
                .andExpect(
                        jsonPath("$.timestamp")
                                .exists()
                )
                .andExpect(
                        jsonPath("$.path")
                                .value(
                                        "/api/auth/register"
                                )
                );
    }

    @Test
    void invalidRegistrationReturnsFieldErrors()
            throws Exception {

        RegisterRequest request =
                new RegisterRequest();

        request.setEmail(
                "not-email"
        );

        request.setPassword(
                "123"
        );

        mockMvc.perform(
                        post(
                                "/api/auth/register"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        objectMapper
                                                .writeValueAsString(
                                                        request
                                                )
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
                        jsonPath("$.fieldErrors.email")
                                .exists()
                )
                .andExpect(
                        jsonPath("$.fieldErrors.password")
                                .exists()
                );

        verifyNoInteractions(
                authService
        );
    }

    private static RegisterRequest validRequest() {

        RegisterRequest request =
                new RegisterRequest();

        request.setEmail(
                "test@example.com"
        );

        request.setPassword(
                "password123"
        );

        return request;
    }
}