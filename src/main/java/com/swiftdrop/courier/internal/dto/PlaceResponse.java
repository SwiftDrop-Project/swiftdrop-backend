package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.shared.reference.District;

/** Where a parcel comes from or goes to, without personal data: shown even when the contact is not. */
public record PlaceResponse(String city, District district) {

    static PlaceResponse from(DeliveryContact contact) {
        return contact == null ? null : new PlaceResponse(contact.city(), contact.district());
    }
}
