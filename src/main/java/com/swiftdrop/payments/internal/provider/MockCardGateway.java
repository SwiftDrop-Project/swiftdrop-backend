package com.swiftdrop.payments.internal.provider;

import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;

/**
 * A pretend card processor: no money moves and nothing leaves SwiftDrop. Only well-known test card numbers are
 * accepted, so a real card can never be entered by mistake:
 * <ul>
 *   <li>4242 4242 4242 4242 (Visa) and 5555 5555 5555 4444 (Mastercard) are approved;</li>
 *   <li>4000 0000 0000 0002 is declined (402 CARD_DECLINED), to try the error.</li>
 * </ul>
 * The card number, expiry and security code are never stored or logged; the caller keeps only the brand and last 4.
 */
@Component
public class MockCardGateway {

    private static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    private static final Map<String, String> APPROVED = Map.of(
            "4242424242424242", "VISA",
            "5555555555554444", "MASTERCARD");
    private static final String DECLINED = "4000000000000002";

    /** An approved charge: what may be kept. */
    public record Charge(String brand, String last4) {
    }

    /**
     * @param cardNumber digits, spaces allowed
     * @param expiry     MM/YY (already checked for its format)
     */
    public Charge charge(String cardNumber, String expiry) {
        String digits = cardNumber.replaceAll("\\s", "");
        if (!APPROVED.containsKey(digits) && !DECLINED.equals(digits)) {
            throw new FieldValidationException("cardNumber", "Use a test card, like 4242 4242 4242 4242.");
        }
        YearMonth expires = YearMonth.of(2000 + Integer.parseInt(expiry.substring(3, 5)),
                Integer.parseInt(expiry.substring(0, 2)));
        if (expires.isBefore(YearMonth.now(SRI_LANKA))) {
            throw new FieldValidationException("expiry", "This card has expired.");
        }
        if (DECLINED.equals(digits)) {
            throw new BusinessRuleException(ErrorCode.CARD_DECLINED);
        }
        return new Charge(APPROVED.get(digits), digits.substring(digits.length() - 4));
    }
}
