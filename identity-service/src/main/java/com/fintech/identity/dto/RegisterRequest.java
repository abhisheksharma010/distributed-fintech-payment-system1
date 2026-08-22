package com.fintech.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(
            message = "email is required"
    )
    @Email(
            message = "email must be valid"
    )
    private String email;

    @NotBlank(
            message = "password is required"
    )
    @Size(
            min = 8,
            max = 100,
            message = "password must be between 8 and 100 characters"
    )
    private String password;
}