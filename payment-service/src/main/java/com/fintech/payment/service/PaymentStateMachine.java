package com.fintech.payment.service;

import com.fintech.payment.entity.Payment;
import com.fintech.payment.entity.PaymentStatus;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

import java.util.Map;
import java.util.Set;

@Component
public class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> transitions = Map.of(
            PaymentStatus.CREATED, Set.of(PaymentStatus.PROCESSING),
            PaymentStatus.PROCESSING, Set.of(PaymentStatus.COMPLETED, PaymentStatus.FAILED),
            PaymentStatus.COMPLETED, Set.of(),
            PaymentStatus.FAILED, Set.of()
    );

    public static void validateTransition(PaymentStatus current, PaymentStatus next) {

        Set<PaymentStatus> allowed = transitions.get(current);

        if (!allowed.contains(next)) {
            throw new IllegalStateException(
                    "Invalid payment status transition: " + current + " → " + next
            );
        }
    }

    public void transition(Payment payment, PaymentStatus nextStatus) {

        validateTransition(payment.getStatus(), nextStatus);

        payment.setStatus(nextStatus);
        payment.setUpdatedAt(LocalDateTime.now());
    }
}