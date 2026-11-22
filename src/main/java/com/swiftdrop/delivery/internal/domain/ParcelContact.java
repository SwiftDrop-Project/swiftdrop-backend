package com.swiftdrop.delivery.internal.domain;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.shared.reference.District;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A pickup or recipient contact stored inside the parcels table (pickup_* and recipient_* columns).
 * Parcel maps it twice and gives the column names. Personal data: no toString on purpose.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParcelContact {

    private String name;
    private String phone;
    private String addressLine;
    private String city;

    @Enumerated(EnumType.STRING)
    private District district;

    public ParcelContact(String name, String phone, String addressLine, String city, District district) {
        this.name = name;
        this.phone = phone;
        this.addressLine = addressLine;
        this.city = city;
        this.district = district;
    }

    /** From the public contract type, e.g. the pickup in CreateDeliveryCommand. */
    public static ParcelContact from(DeliveryContact contact) {
        return new ParcelContact(contact.name(), contact.phone(), contact.addressLine(), contact.city(),
                contact.district());
    }

    /** Back to the public contract type, for DeliverySnapshot. */
    public DeliveryContact toContact() {
        return new DeliveryContact(name, phone, addressLine, city, district);
    }
}
