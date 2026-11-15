package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.internal.dto.SignUpFields.EmailAddress;
import com.swiftdrop.identity.internal.dto.SignUpFields.PersonName;
import com.swiftdrop.shared.validation.SriLankanMobile;

/**
 * Body of PUT /admin/users/{id}: the details an admin may edit for any account. Role, status, password and staff
 * fields (branch, vehicle) are deliberately not here: status changes go through PATCH /admin/users/{id}/status and
 * courier/dispatcher details through their own pages.
 */
public record UpdateUserRequest(
        @PersonName String fullName,
        @SriLankanMobile String phone,
        @EmailAddress String email) {

    /** Personal data must never reach the logs. */
    @Override
    public String toString() {
        return "UpdateUserRequest[]";
    }
}
