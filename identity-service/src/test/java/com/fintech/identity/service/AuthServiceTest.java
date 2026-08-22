package com.fintech.identity.service;

import com.fintech.identity.dto.RegisterRequest;
import com.fintech.identity.entity.Role;
import com.fintech.identity.entity.User;
import com.fintech.identity.exception.EmailAlreadyRegisteredException;
import com.fintech.identity.repository.RoleRepository;
import com.fintech.identity.repository.UserRepository;
import com.fintech.identity.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {

        authService =
                new AuthService(
                        userRepository,
                        roleRepository,
                        passwordEncoder,
                        authenticationManager,
                        jwtService
                );
    }

    @Test
    void duplicateEmailIsRejectedBeforeInsert() {

        RegisterRequest request =
                request();

        when(
                userRepository.existsByEmail(
                        request.getEmail()
                )
        ).thenReturn(true);

        assertThrows(
                EmailAlreadyRegisteredException.class,
                () ->
                        authService.register(
                                request
                        )
        );

        verify(
                userRepository,
                never()
        ).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateEmailRaceBecomesBusinessConflict() {

        RegisterRequest request =
                request();

        Role role =
                Role.builder()
                        .name("USER")
                        .build();

        when(
                userRepository.existsByEmail(
                        request.getEmail()
                )
        ).thenReturn(false);

        when(
                roleRepository.findByName(
                        "USER"
                )
        ).thenReturn(
                Optional.of(role)
        );

        when(
                passwordEncoder.encode(
                        request.getPassword()
                )
        ).thenReturn(
                "encoded-password"
        );

        when(
                userRepository.saveAndFlush(
                        any(User.class)
                )
        ).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate email"
                )
        );

        assertThrows(
                EmailAlreadyRegisteredException.class,
                () ->
                        authService.register(
                                request
                        )
        );
    }

    private static RegisterRequest request() {

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