package com.swiftdrop.payments.internal.dto;

/**
 * POST /payments/card-check: the mock card is approved. Nothing was charged or stored; only the brand and the last 4
 * digits come back, for "Card ···· 4242" on the Create delivery page.
 */
public record CardCheckResponse(String cardBrand, String cardLast4) {
}
