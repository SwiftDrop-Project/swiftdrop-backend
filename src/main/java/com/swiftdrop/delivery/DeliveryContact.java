package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;

/**
 * A pickup or delivery contact and address on a parcel. {@code phone} is stored as {@code +947XXXXXXXX}.
 * Never log these values (they are personal data).
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier).
 */
public record DeliveryContact(String name, String phone, String addressLine, String city, District district) {
}
