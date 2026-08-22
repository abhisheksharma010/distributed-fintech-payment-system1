package com.fintech.payment.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CreatePaymentRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {

        ValidatorFactory factory =
                Validation.buildDefaultValidatorFactory();

        validator = factory.getValidator();
    }

    @Test
    void rejectsNullSender() {

        CreatePaymentRequest request = validRequest();
        request.setSenderId(null);

        assertHasViolation(request, "senderId");
    }

    @Test
    void rejectsNullReceiver() {

        CreatePaymentRequest request = validRequest();
        request.setReceiverId(null);

        assertHasViolation(request, "receiverId");
    }

    @Test
    void rejectsNullAmount() {

        CreatePaymentRequest request = validRequest();
        request.setAmount(null);

        assertHasViolation(request, "amount");
    }

    @Test
    void rejectsZeroAmount() {

        CreatePaymentRequest request = validRequest();
        request.setAmount(BigDecimal.ZERO);

        assertHasViolation(request, "amount");
    }

    @Test
    void rejectsNegativeAmount() {

        CreatePaymentRequest request = validRequest();
        request.setAmount(BigDecimal.valueOf(-100));

        assertHasViolation(request, "amount");
    }

    @Test
    void rejectsExcessiveDecimalPrecision() {

        CreatePaymentRequest request = validRequest();
        request.setAmount(new BigDecimal("10.001"));

        assertHasViolation(request, "amount");
    }

    @Test
    void rejectsAmountExceedingDatabasePrecision() {

        CreatePaymentRequest request = validRequest();
        request.setAmount(new BigDecimal("10000000000.00"));

        assertHasViolation(request, "amount");
    }

    @Test
    void rejectsNullCurrency() {

        CreatePaymentRequest request = validRequest();
        request.setCurrency(null);

        assertHasViolation(request, "currency");
    }

    @Test
    void rejectsBlankCurrency() {

        CreatePaymentRequest request = validRequest();
        request.setCurrency(" ");

        assertHasViolation(request, "currency");
    }

    @Test
    void rejectsUnsupportedCurrency() {

        CreatePaymentRequest request = validRequest();
        request.setCurrency("USD");

        assertHasViolation(request, "currency");
    }

    @Test
    void rejectsNullIdempotencyKey() {

        CreatePaymentRequest request = validRequest();
        request.setIdempotencyKey(null);

        assertHasViolation(request, "idempotencyKey");
    }

    @Test
    void rejectsBlankIdempotencyKey() {

        CreatePaymentRequest request = validRequest();
        request.setIdempotencyKey(" ");

        assertHasViolation(request, "idempotencyKey");
    }

    @Test
    void rejectsIdempotencyKeyLongerThanDatabaseColumn() {

        CreatePaymentRequest request = validRequest();
        request.setIdempotencyKey("a".repeat(256));

        assertHasViolation(request, "idempotencyKey");
    }

    private static CreatePaymentRequest validRequest() {

        CreatePaymentRequest request =
                new CreatePaymentRequest();

        request.setSenderId(UUID.randomUUID());
        request.setReceiverId(UUID.randomUUID());
        request.setAmount(new BigDecimal("2000.00"));
        request.setCurrency("INR");
        request.setIdempotencyKey("payment-abc-123");

        return request;
    }

    private static void assertHasViolation(
            CreatePaymentRequest request,
            String property
    ) {

        Set<String> properties =
                validator.validate(request)
                        .stream()
                        .map(v -> v.getPropertyPath().toString())
                        .collect(Collectors.toSet());

        assertTrue(
                properties.contains(property),
                () -> "Expected validation violation for " + property
        );
    }
}