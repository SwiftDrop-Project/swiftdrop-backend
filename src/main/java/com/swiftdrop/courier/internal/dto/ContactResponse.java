package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.shared.reference.District;

/** A pickup or delivery contact as the courier sees it. {@code phone} is {@code +947XXXXXXXX}. */
public record ContactResponse(String name, String phone, String address, String city, District district) {

    static ContactResponse from(DeliveryContact contact) {
        if (contact == null) {
            return null;
        }
        return new ContactResponse(contact.name(), contact.phone(), contact.addressLine(), contact.city(),
                contact.district());
    }
}
