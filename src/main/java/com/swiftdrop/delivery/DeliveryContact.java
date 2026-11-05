package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;

import java.util.Objects;

/**
 * A pickup or delivery contact and address on a parcel. {@code phone} is stored as {@code +947XXXXXXXX}.
 * Never log these values (they are personal data): {@link #toString()} shows only the city and district.
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier).
 */
public record DeliveryContact(String name, String phone, String addressLine, String city, District district) {

    public DeliveryContact {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(phone, "phone");
        Objects.requireNonNull(addressLine, "addressLine");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(district, "district");
    }

    /** Only the city and district: names, phone numbers and street addresses must never reach the logs. */
    @Override
    public String toString() {
        return "DeliveryContact[city=" + city + ", district=" + district + "]";
    }
}
