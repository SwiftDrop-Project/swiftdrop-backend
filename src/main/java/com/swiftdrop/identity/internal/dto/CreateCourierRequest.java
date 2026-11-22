package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.internal.dto.SignUpFields.EmailAddress;
import com.swiftdrop.identity.internal.dto.SignUpFields.Password;
import com.swiftdrop.identity.internal.dto.SignUpFields.PersonName;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.VehicleType;
import com.swiftdrop.shared.validation.SriLankanMobile;
import jakarta.validation.constraints.NotNull;

/** Body of POST /admin/couriers. */
public record CreateCourierRequest(
        @PersonName String fullName,
        @SriLankanMobile String phone,
        @EmailAddress String email,
        @NotNull(message = "Select a branch.") District branch,
        @NotNull(message = "Select a vehicle type.") VehicleType vehicleType,
        @Password String password) {

    @Override
    public String toString() {
        return "CreateCourierRequest[]";
    }
}

