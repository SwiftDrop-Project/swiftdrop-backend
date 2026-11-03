package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.internal.dto.SignUpFields.Password;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Body of POST /auth/password/change. */
public record ChangePasswordRequest(
        @Schema(example = "secret123") @NotBlank(message = "Enter your current password.") String currentPassword,
        @Schema(example = "newSecret456") @Password String newPassword) {

    /** Keeps the passwords out of logs. */
    @Override
    public String toString() {
        return "ChangePasswordRequest[]";
    }
}
