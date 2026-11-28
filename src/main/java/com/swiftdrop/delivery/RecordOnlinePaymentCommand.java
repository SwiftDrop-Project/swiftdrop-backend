package com.swiftdrop.delivery;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Input for {@link DeliveryApi#recordOnlinePayment}: the payments module (WP2) verified an online payment of the
 * parcel's delivery fee. There is no amount: delivery records the parcel's own fee and currency.
 *
 * <p>The caller is responsible for the verification (today WP2's mock card gateway, for the academic demonstration;
 * not a real payment provider). Delivery only records the result, once per parcel.
 *
 * @param externalReference the payment's reference in the module that verified it, e.g.
 *                          {@code payments:3f1d2c4b-0b6c-4a51-9d2e-3a4f5b6c7d8e} (the WP2 payment record's id).
 *                          1-100 characters: letters, digits and {@code : _ - .}, starting with a letter or digit.
 *                          Never card data
 */
public record RecordOnlinePaymentCommand(UUID parcelId, String externalReference) {

    /** The longest reference: delivery.fee_payments.external_reference is VARCHAR(100). */
    public static final int MAX_REFERENCE_LENGTH = 100;

    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:_.-]{0,99}");

    public RecordOnlinePaymentCommand {
        Objects.requireNonNull(parcelId, "parcelId");
        Objects.requireNonNull(externalReference, "externalReference");
        if (!REFERENCE.matcher(externalReference).matches()) {
            throw new IllegalArgumentException("externalReference must be 1-" + MAX_REFERENCE_LENGTH
                    + " characters: letters, digits and : _ - . (starting with a letter or digit)");
        }
    }
}
