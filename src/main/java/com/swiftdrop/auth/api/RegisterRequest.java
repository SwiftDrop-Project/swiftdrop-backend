package com.swiftdrop.auth.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.swiftdrop.merchants.domain.ParcelsPerMonth;
import com.swiftdrop.shared.meta.District;
import com.swiftdrop.shared.util.SriLankanMobile;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /auth/register. {@code accountType} picks the shape: CUSTOMER or MERCHANT.
 * Validation messages are the ones in the API.md table, so the frontend can show them as-is.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "accountType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RegisterRequest.Customer.class, name = "CUSTOMER"),
        @JsonSubTypes.Type(value = RegisterRequest.Merchant.class, name = "MERCHANT")
})
@Schema(oneOf = {RegisterRequest.Customer.class, RegisterRequest.Merchant.class},
        discriminatorProperty = "accountType",
        discriminatorMapping = {
                @DiscriminatorMapping(value = "CUSTOMER", schema = RegisterRequest.Customer.class),
                @DiscriminatorMapping(value = "MERCHANT", schema = RegisterRequest.Merchant.class)
        })
public sealed interface RegisterRequest permits RegisterRequest.Customer, RegisterRequest.Merchant {

    String NAME_MESSAGE = "Enter your full name.";
    String PHONE_MESSAGE = "Enter a Sri Lankan mobile number, like 077 123 4567.";
    String EMAIL_MESSAGE = "Enter a full email address, like name@example.com";
    String PASSWORD_MESSAGE = "Use at least 8 characters, including a number.";
    String TERMS_MESSAGE = "Accept the terms to continue.";
    /** 8-72 characters with at least one letter and one digit. */
    String PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$";
    /** Requires a dot in the domain part, e.g. name@example.com. */
    String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    String phone();

    String email();

    String password();

    @Schema(name = "CustomerRegisterRequest")
    record Customer(
            @Schema(example = "Ayesha Fernando")
            @NotBlank(message = NAME_MESSAGE) @Size(min = 2, max = 100, message = NAME_MESSAGE)
            String fullName,

            @Schema(example = "077 123 4567")
            @SriLankanMobile(message = PHONE_MESSAGE)
            String phone,

            @Schema(example = "ayesha@example.com")
            @NotBlank(message = EMAIL_MESSAGE) @Size(max = 254, message = EMAIL_MESSAGE)
            @Email(regexp = EMAIL_PATTERN, message = EMAIL_MESSAGE)
            String email,

            @Schema(example = "secret123")
            @NotNull(message = PASSWORD_MESSAGE) @Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_MESSAGE)
            String password,

            @Schema(example = "true")
            @NotNull(message = TERMS_MESSAGE) @AssertTrue(message = TERMS_MESSAGE)
            Boolean acceptTerms) implements RegisterRequest {

        @Override
        public String toString() {
            return "RegisterRequest.Customer[]";
        }
    }

    @Schema(name = "MerchantRegisterRequest")
    record Merchant(
            @Schema(example = "Lanka Crafts")
            @NotBlank(message = "Enter your business name.")
            @Size(min = 2, max = 120, message = "Enter your business name.")
            String businessName,

            @Schema(example = "PV 00012345", nullable = true)
            @Size(max = 30, message = "Use at most 30 characters.")
            String businessRegNo,

            @NotNull(message = "Select a range.")
            ParcelsPerMonth parcelsPerMonth,

            @Schema(example = "Nimal Perera")
            @NotBlank(message = NAME_MESSAGE) @Size(min = 2, max = 100, message = NAME_MESSAGE)
            String contactPerson,

            @Schema(example = "0712345678")
            @SriLankanMobile(message = PHONE_MESSAGE)
            String phone,

            @Schema(example = "orders@lankacrafts.lk")
            @NotBlank(message = EMAIL_MESSAGE) @Size(max = 254, message = EMAIL_MESSAGE)
            @Email(regexp = EMAIL_PATTERN, message = EMAIL_MESSAGE)
            String email,

            @Schema(example = "COLOMBO")
            @NotNull(message = "Select a district.")
            District pickupDistrict,

            @Schema(example = "Nugegoda")
            @NotBlank(message = "Enter your city or town.")
            @Size(min = 2, max = 80, message = "Enter your city or town.")
            String city,

            @Schema(example = "secret123")
            @NotNull(message = PASSWORD_MESSAGE) @Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_MESSAGE)
            String password,

            @Schema(example = "true")
            @NotNull(message = TERMS_MESSAGE) @AssertTrue(message = TERMS_MESSAGE)
            Boolean acceptTerms) implements RegisterRequest {

        @Override
        public String toString() {
            return "RegisterRequest.Merchant[]";
        }
    }
}
