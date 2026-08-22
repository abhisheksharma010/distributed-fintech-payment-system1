package com.fintech.identity.service;

import com.fintech.identity.dto.LoginRequest;
import com.fintech.identity.dto.RegisterRequest;
import com.fintech.identity.entity.Role;
import com.fintech.identity.entity.User;
import com.fintech.identity.exception.EmailAlreadyRegisteredException;
import com.fintech.identity.repository.RoleRepository;
import com.fintech.identity.repository.UserRepository;
import com.fintech.identity.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public void register(
            RegisterRequest request
    ) {

        String email =
                request.getEmail()
                        .trim()
                        .toLowerCase();

        if (
                userRepository
                        .existsByEmail(email)
        ) {

            throw new EmailAlreadyRegisteredException();
        }

        Role userRole =
                roleRepository
                        .findByName("USER")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Required USER role is not configured"
                                        )
                        );

        User user =
                User.builder()
                        .email(email)
                        .passwordHash(
                                passwordEncoder
                                        .encode(
                                                request.getPassword()
                                        )
                        )
                        .status("ACTIVE")
                        .createdAt(
                                LocalDateTime.now()
                        )
                        .roles(
                                Set.of(userRole)
                        )
                        .build();

        try {

            /*
             * saveAndFlush forces the database unique-email
             * constraint to be checked here, allowing a
             * concurrent registration race to become a
             * deterministic 409.
             */
            userRepository
                    .saveAndFlush(user);

        } catch (
                DataIntegrityViolationException ex
        ) {

            throw new EmailAlreadyRegisteredException();
        }
    }

    public String login(
            LoginRequest request
    ) {

        String email =
                request.getEmail()
                        .trim()
                        .toLowerCase();

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        email,
                        request.getPassword()
                )
        );

        User user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Invalid login credentials"
                                        )
                        );

        Map<String, Object> claims =
                Map.of(
                        "userId",
                        user.getId(),

                        "roles",
                        user.getRoles()
                                .stream()
                                .map(
                                        Role::getName
                                )
                                .collect(
                                        Collectors.toList()
                                )
                );

        return jwtService.generateToken(
                user.getEmail(),
                claims
        );
    }
}