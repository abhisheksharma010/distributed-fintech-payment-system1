package com.fintech.payment.dto;

import java.util.List;

public record PaymentPageResponse(
        List<PaymentResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}