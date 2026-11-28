package com.swiftdrop.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.swiftdrop.customer.internal.domain.CustomerAddress;
import com.swiftdrop.shared.reference.District;

import java.time.Instant;
import java.util.UUID;

/** One saved address as the customer sees it. {@code isDefault}: it is the customer's default pickup address. */
public record CustomerAddressResponse(
        UUID id,
        String label,
        String contactName,
        String phone,
        String addressLine,
        String city,
        District district,
        String postalCode,
        String landmark,
        @JsonProperty("isDefault") boolean isDefault,
        Instant createdAt,
        Instant updatedAt) {

    public static CustomerAddressResponse from(CustomerAddress address, boolean isDefault) {
        return new CustomerAddressResponse(address.getId(), address.getLabel(), address.getContactName(),
                address.getPhone(), address.getAddressLine(), address.getCity(), address.getDistrict(),
                address.getPostalCode(), address.getLandmark(), isDefault, address.getCreatedAt(),
                address.getUpdatedAt());
    }

    /** Contacts are personal data: keep them out of logs. */
    @Override
    public String toString() {
        return "CustomerAddressResponse[id=" + id + ", label=" + label + ", isDefault=" + isDefault + "]";
    }
}
