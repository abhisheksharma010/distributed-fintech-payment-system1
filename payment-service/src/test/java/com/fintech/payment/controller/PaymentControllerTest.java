package com.fintech.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.dto.CreatePaymentRequest;
import com.fintech.payment.dto.PaymentPageResponse;
import com.fintech.payment.dto.PaymentResponse;
import com.fintech.payment.entity.PaymentStatus;
import com.fintech.payment.exception.IdempotencyKeyReusedException;
import com.fintech.payment.exception.PaymentAccessDeniedException;
import com.fintech.payment.exception.PaymentNotFoundException;
import com.fintech.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentService paymentService;

    @Test
    void validRequestReturnsPaymentTransferContract()
            throws Exception {

        UUID senderId =
                UUID.randomUUID();

        UUID receiverId =
                UUID.randomUUID();

        UUID paymentId =
                UUID.randomUUID();

        CreatePaymentRequest request =
                request(
                        senderId,
                        receiverId
                );

        PaymentResponse response =
                response(
                        paymentId,
                        senderId,
                        receiverId,
                        PaymentStatus.PROCESSING,
                        null
                );

        when(
                paymentService.createPayment(
                        any(CreatePaymentRequest.class)
                )
        ).thenReturn(response);

        mockMvc.perform(
                        post("/api/payments/pay")
                                .header(
                                        "X-USER-ID",
                                        senderId.toString()
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
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.paymentId")
                                .value(
                                        paymentId.toString()
                                )
                )
                .andExpect(
                        jsonPath("$.senderId")
                                .value(
                                        senderId.toString()
                                )
                )
                .andExpect(
                        jsonPath("$.receiverId")
                                .value(
                                        receiverId.toString()
                                )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("PROCESSING")
                );

        verify(paymentService)
                .createPayment(
                        any(CreatePaymentRequest.class)
                );
    }

    @Test
    void senderCanGetPayment()
            throws Exception {

        UUID senderId =
                UUID.randomUUID();

        UUID receiverId =
                UUID.randomUUID();

        UUID paymentId =
                UUID.randomUUID();

        when(
                paymentService.getPayment(
                        paymentId,
                        senderId,
                        Set.of("USER")
                )
        ).thenReturn(
                response(
                        paymentId,
                        senderId,
                        receiverId,
                        PaymentStatus.COMPLETED,
                        null
                )
        );

        mockMvc.perform(
                        get(
                                "/api/payments/{paymentId}",
                                paymentId
                        )
                                .header(
                                        "X-USER-ID",
                                        senderId.toString()
                                )
                                .header(
                                        "X-USER-ROLES",
                                        "USER"
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.paymentId")
                                .value(
                                        paymentId.toString()
                                )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("COMPLETED")
                );
    }

    @Test
    void receiverCanGetPayment()
            throws Exception {

        UUID senderId =
                UUID.randomUUID();

        UUID receiverId =
                UUID.randomUUID();

        UUID paymentId =
                UUID.randomUUID();

        when(
                paymentService.getPayment(
                        paymentId,
                        receiverId,
                        Set.of("USER")
                )
        ).thenReturn(
                response(
                        paymentId,
                        senderId,
                        receiverId,
                        PaymentStatus.COMPLETED,
                        null
                )
        );

        mockMvc.perform(
                        get(
                                "/api/payments/{paymentId}",
                                paymentId
                        )
                                .header(
                                        "X-USER-ID",
                                        receiverId.toString()
                                )
                                .header(
                                        "X-USER-ROLES",
                                        "USER"
                                )
                )
                .andExpect(
                        status().isOk()
                );
    }

    @Test
    void unrelatedUserCannotGetPayment()
            throws Exception {

        UUID paymentId =
                UUID.randomUUID();

        UUID userId =
                UUID.randomUUID();

        when(
                paymentService.getPayment(
                        paymentId,
                        userId,
                        Set.of("USER")
                )
        ).thenThrow(
                new PaymentAccessDeniedException(
                        paymentId
                )
        );

        mockMvc.perform(
                        get(
                                "/api/payments/{paymentId}",
                                paymentId
                        )
                                .header(
                                        "X-USER-ID",
                                        userId.toString()
                                )
                                .header(
                                        "X-USER-ROLES",
                                        "USER"
                                )
                )
                .andExpect(
                        status().isForbidden()
                )
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "PAYMENT_ACCESS_DENIED"
                                )
                )
                .andExpect(
                        jsonPath("$.timestamp")
                                .exists()
                )
                .andExpect(
                        jsonPath("$.path")
                                .value(
                                        "/api/payments/"
                                                + paymentId
                                )
                );
    }

    @Test
    void missingPaymentReturnsStructuredNotFound()
            throws Exception {

        UUID paymentId =
                UUID.randomUUID();

        UUID userId =
                UUID.randomUUID();

        when(
                paymentService.getPayment(
                        paymentId,
                        userId,
                        Set.of("USER")
                )
        ).thenThrow(
                new PaymentNotFoundException(
                        paymentId
                )
        );

        mockMvc.perform(
                        get(
                                "/api/payments/{paymentId}",
                                paymentId
                        )
                                .header(
                                        "X-USER-ID",
                                        userId.toString()
                                )
                                .header(
                                        "X-USER-ROLES",
                                        "USER"
                                )
                )
                .andExpect(
                        status().isNotFound()
                )
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "PAYMENT_NOT_FOUND"
                                )
                );
    }

    @Test
    void failedPaymentResponseContainsFailureReason()
            throws Exception {

        UUID paymentId =
                UUID.randomUUID();

        UUID senderId =
                UUID.randomUUID();

        UUID receiverId =
                UUID.randomUUID();

        when(
                paymentService.getPayment(
                        paymentId,
                        senderId,
                        Set.of("USER")
                )
        ).thenReturn(
                response(
                        paymentId,
                        senderId,
                        receiverId,
                        PaymentStatus.FAILED,
                        "INSUFFICIENT_BALANCE"
                )
        );

        mockMvc.perform(
                        get(
                                "/api/payments/{paymentId}",
                                paymentId
                        )
                                .header(
                                        "X-USER-ID",
                                        senderId.toString()
                                )
                                .header(
                                        "X-USER-ROLES",
                                        "USER"
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("FAILED")
                )
                .andExpect(
                        jsonPath("$.failureReason")
                                .value(
                                        "INSUFFICIENT_BALANCE"
                                )
                );
    }

    @Test
    void myPaymentsReturnsPaginatedHistory()
            throws Exception {

        UUID userId =
                UUID.randomUUID();

        PaymentResponse first =
                response(
                        UUID.randomUUID(),
                        userId,
                        UUID.randomUUID(),
                        PaymentStatus.COMPLETED,
                        null
                );

        PaymentResponse second =
                response(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        userId,
                        PaymentStatus.PROCESSING,
                        null
                );

        PaymentPageResponse page =
                new PaymentPageResponse(
                        List.of(
                                first,
                                second
                        ),
                        0,
                        10,
                        2,
                        1,
                        true,
                        true
                );

        when(
                paymentService.getMyPayments(
                        userId,
                        0,
                        10
                )
        ).thenReturn(page);

        mockMvc.perform(
                        get(
                                "/api/payments/mine"
                        )
                                .header(
                                        "X-USER-ID",
                                        userId.toString()
                                )
                                .param(
                                        "page",
                                        "0"
                                )
                                .param(
                                        "size",
                                        "10"
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.content.length()")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.page")
                                .value(0)
                )
                .andExpect(
                        jsonPath("$.size")
                                .value(10)
                )
                .andExpect(
                        jsonPath("$.totalElements")
                                .value(2)
                )
                .andExpect(
                        jsonPath("$.totalPages")
                                .value(1)
                );
    }

    @Test
    void invalidPageReturnsMeaningfulBadRequest()
            throws Exception {

        UUID userId =
                UUID.randomUUID();

        when(
                paymentService.getMyPayments(
                        userId,
                        -1,
                        10
                )
        ).thenThrow(
                new IllegalArgumentException(
                        "page must be greater than or equal to 0"
                )
        );

        mockMvc.perform(
                        get(
                                "/api/payments/mine"
                        )
                                .header(
                                        "X-USER-ID",
                                        userId.toString()
                                )
                                .param(
                                        "page",
                                        "-1"
                                )
                                .param(
                                        "size",
                                        "10"
                                )
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "INVALID_REQUEST"
                                )
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "page must be greater than or equal to 0"
                                )
                );
    }

    @Test
    void malformedPaymentUuidReturnsStructuredBadRequest()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/api/payments/not-a-uuid"
                        )
                                .header(
                                        "X-USER-ID",
                                        UUID.randomUUID()
                                                .toString()
                                )
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "INVALID_REQUEST"
                                )
                );
    }

    @Test
    void validationFailureReturnsFieldErrors()
            throws Exception {

        UUID senderId =
                UUID.randomUUID();

        String body =
                """
                {
                  "senderId": "%s",
                  "receiverId": "%s",
                  "amount": 0,
                  "currency": "",
                  "idempotencyKey": ""
                }
                """.formatted(
                        senderId,
                        UUID.randomUUID()
                );

        mockMvc.perform(
                        post(
                                "/api/payments/pay"
                        )
                                .header(
                                        "X-USER-ID",
                                        senderId.toString()
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(body)
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
                        jsonPath("$.message")
                                .value(
                                        "Request validation failed"
                                )
                )
                .andExpect(
                        jsonPath("$.fieldErrors")
                                .isMap()
                );

        verifyNoInteractions(
                paymentService
        );
    }

    @Test
    void reusedIdempotencyKeyReturnsConflict()
            throws Exception {

        CreatePaymentRequest request =
                request(
                        UUID.randomUUID(),
                        UUID.randomUUID()
                );

        when(
                paymentService.createPayment(
                        any(
                                CreatePaymentRequest.class
                        )
                )
        ).thenThrow(
                new IdempotencyKeyReusedException(
                        request.getIdempotencyKey()
                )
        );

        mockMvc.perform(
                        post(
                                "/api/payments/pay"
                        )
                                .header(
                                        "X-USER-ID",
                                        request
                                                .getSenderId()
                                                .toString()
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
                                        "IDEMPOTENCY_KEY_REUSED"
                                )
                );
    }

    @Test
    void missingAuthenticatedUserReturnsUnauthorized()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/api/payments/mine"
                        )
                )
                .andExpect(
                        status().isUnauthorized()
                );

        verifyNoInteractions(
                paymentService
        );
    }

    private static CreatePaymentRequest request(
            UUID senderId,
            UUID receiverId
    ) {

        CreatePaymentRequest request =
                new CreatePaymentRequest();

        request.setSenderId(senderId);
        request.setReceiverId(receiverId);

        request.setAmount(
                new BigDecimal(
                        "2000.00"
                )
        );

        request.setCurrency("INR");

        request.setIdempotencyKey(
                "payment-abc-123"
        );

        return request;
    }

    private static PaymentResponse response(
            UUID paymentId,
            UUID senderId,
            UUID receiverId,
            PaymentStatus status,
            String failureReason
    ) {

        LocalDateTime now =
                LocalDateTime.now();

        return PaymentResponse.builder()
                .paymentId(paymentId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(
                        new BigDecimal(
                                "2000.00"
                        )
                )
                .currency("INR")
                .status(status)
                .failureReason(
                        failureReason
                )
                .createdAt(
                        now.minusMinutes(1)
                )
                .updatedAt(now)
                .build();
    }
}