package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.internal.dto.SignUpFields.Password;

/** Body of POST /admin/couriers/{id}/reset-password. */
public record AdminResetPasswordRequest(@Password String newPassword) {

    @Override
    public String toString() {
        return "AdminResetPasswordRequest[]";
    }
}

