package com.swiftdrop.payments.internal.dto;

import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.payments.internal.domain.Payment;
import com.swiftdrop.payments.internal.domain.PaymentStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

/**
 * The two cards on the Payments page. "Due" is split into cash (to hand to the courier) and online (to pay here by
 * card). {@code dueCashTrackingNumber} is set only when exactly one cash payment is due, so the page can name it.
 */
public record PaymentSummaryResponse(
        String currency,
        BigDecimal paidTotal,
        long paidCount,
        BigDecimal dueTotal,
        long dueCount,
        BigDecimal dueCashTotal,
        long dueCashCount,
        BigDecimal dueOnlineTotal,
        long dueOnlineCount,
        long notChargedCount,
        String dueCashTrackingNumber) {

    public static PaymentSummaryResponse of(List<Payment> payments) {
        Predicate<Payment> paid = payment -> payment.getStatus() == PaymentStatus.PAID;
        Predicate<Payment> due = payment -> payment.getStatus() == PaymentStatus.DUE;
        Predicate<Payment> online = payment -> payment.getMethod() == PaymentMethod.ONLINE;
        List<Payment> dueCash = payments.stream().filter(due.and(online.negate())).toList();
        return new PaymentSummaryResponse(
                "LKR",
                total(payments, paid), count(payments, paid),
                total(payments, due), count(payments, due),
                total(payments, due.and(online.negate())), dueCash.size(),
                total(payments, due.and(online)), count(payments, due.and(online)),
                count(payments, payment -> payment.getStatus() == PaymentStatus.NOT_CHARGED),
                dueCash.size() == 1 ? dueCash.getFirst().getTrackingNumber() : null);
    }

    private static BigDecimal total(List<Payment> payments, Predicate<Payment> which) {
        return payments.stream().filter(which).map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static long count(List<Payment> payments, Predicate<Payment> which) {
        return payments.stream().filter(which).count();
    }
}
