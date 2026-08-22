package com.fintech.payment.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class CreatePaymentRequest {

    @NotNull
    private UUID senderId;

    @NotNull
    private UUID receiverId;

    @NotNull
    @Positive
    @Digits(integer = 10, fraction = 2)
    private BigDecimal amount;

    @NotBlank
    @Pattern(
            regexp = "INR",
            message = "currency must be a supported currency code: INR"
    )
    private String currency;

    @NotBlank
    @Size(max = 255)
    private String idempotencyKey;
}