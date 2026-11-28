package com.swiftdrop.payments.internal.provider;

import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The mock card accepts only test numbers, keeps only brand and last 4, and refuses expired cards. */
class MockCardGatewayTest {

    private static final DateTimeFormatter MM_YY = DateTimeFormatter.ofPattern("MM/yy");
    private static final String NEXT_YEAR = YearMonth.now(ZoneId.of("Asia/Colombo")).plusYears(1).format(MM_YY);
    private static final String LAST_MONTH = YearMonth.now(ZoneId.of("Asia/Colombo")).minusMonths(1).format(MM_YY);

    private final MockCardGateway gateway = new MockCardGateway();

    @Test
    void theTestCardsAreApprovedWithTheirBrandAndLastFourDigits() {
        assertThat(gateway.charge("4242 4242 4242 4242", NEXT_YEAR))
                .isEqualTo(new MockCardGateway.Charge("VISA", "4242"));
        assertThat(gateway.charge("5555555555554444", NEXT_YEAR))
                .isEqualTo(new MockCardGateway.Charge("MASTERCARD", "4444"));
    }

    @Test
    void theDeclineTestCardIsDeclined() {
        assertThatThrownBy(() -> gateway.charge("4000 0000 0000 0002", NEXT_YEAR))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CARD_DECLINED));
    }

    @Test
    void anyOtherNumberIsRefusedSoARealCardIsNeverUsed() {
        assertThatThrownBy(() -> gateway.charge("4111 1111 1111 1111", NEXT_YEAR))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.fieldErrors()).containsEntry("cardNumber",
                                "Use a test card, like 4242 4242 4242 4242."));
    }

    @Test
    void anExpiredCardIsRefused() {
        assertThatThrownBy(() -> gateway.charge("4242424242424242", LAST_MONTH))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.fieldErrors()).containsEntry("expiry", "This card has expired."));
    }
}
