package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;

import java.util.Objects;

/**
 * A person and the address where a parcel is picked up or dropped off. {@code phone} is already normalised to
 * {@code +947XXXXXXXX}. Used for both ends of a delivery: the sender at the pickup and the recipient at the drop-off.
 */
public record ContactAddress(String name, String phone, String addressLine, String city, District district) {

    public ContactAddress {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(phone, "phone");
        Objects.requireNonNull(addressLine, "addressLine");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(district, "district");
    }

    /** Only the city and district: names, phone numbers and street addresses must never reach the logs. */
    @Override
    public String toString() {
        return "ContactAddress[city=" + city + ", district=" + district + "]";
    }
}
