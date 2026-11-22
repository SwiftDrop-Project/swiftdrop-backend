package com.swiftdrop.identity;

import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.VehicleType;

import java.util.UUID;

/**
 * A courier as other modules (dispatch, delivery) see it.
 */
public record CourierSummary(
        UUID id,
        String fullName,
        String email,
        String phone,
        District branch,
        VehicleType vehicleType,
        UserStatus status) {

    /** Keeps personal contact details out of logs. */
    @Override
    public String toString() {
        return "CourierSummary[id=" + id + ", branch=" + branch + ", vehicleType=" + vehicleType
                + ", status=" + status + "]";
    }
}
