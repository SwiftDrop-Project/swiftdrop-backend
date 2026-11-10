package com.swiftdrop.pricing.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupee amounts always have two decimals, rounded half up. Never double: it can't hold cents exactly. */
final class Money {

    private Money() {
    }

    static BigDecimal of(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
