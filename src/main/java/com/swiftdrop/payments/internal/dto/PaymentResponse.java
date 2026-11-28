package com.swiftdrop.payments.internal.dto;

import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.payments.internal.domain.Payment;
import com.swiftdrop.payments.internal.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One delivery's payment. {@code method} null: booked before payment methods (cash on delivery). {@code cardBrand}
 * and {@code cardLast4} only after a mock card payment; {@code paidAt} only while {@code PAID}.
 */
public record PaymentResponse(
        UUID id,
        UUID parcelId,
        String trackingNumber,
        Instant bookedAt,
        PaymentMethod method,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String cardBrand,
        String cardLast4,
        Instant paidAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getParcelId(), payment.getTrackingNumber(),
                payment.getBookedAt(), payment.getMethod(), payment.getStatus(), payment.getAmount(),
                payment.getCurrency(), payment.getCardBrand(), payment.getCardLast4(), payment.getPaidAt());
    }
}
