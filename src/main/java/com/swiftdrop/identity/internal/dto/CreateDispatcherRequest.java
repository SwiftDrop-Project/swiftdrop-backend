package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.internal.dto.SignUpFields.EmailAddress;
import com.swiftdrop.identity.internal.dto.SignUpFields.Password;
import com.swiftdrop.identity.internal.dto.SignUpFields.PersonName;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.validation.SriLankanMobile;
import jakarta.validation.constraints.NotNull;

/** Body of POST /admin/dispatchers. */
public record CreateDispatcherRequest(
        @PersonName String fullName,
        @SriLankanMobile String phone,
        @NotNull(message = "Select a branch.") District branch,
        @EmailAddress String email,
        @Password String password) {

    @Override
    public String toString() {
        return "CreateDispatcherRequest[]";
    }
}

