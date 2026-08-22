package com.fintech.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(

        @NotBlank(message = "currency is required")
        @Size(
                min = 3,
                max = 3,
                message = "currency must be a 3-letter code"
        )
        String currency
) {
}