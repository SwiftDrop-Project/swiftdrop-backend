package com.swiftdrop.identity.internal.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.swiftdrop.identity.internal.dto.SignUpFields.AcceptTerms;
import com.swiftdrop.identity.internal.dto.SignUpFields.BusinessName;
import com.swiftdrop.identity.internal.dto.SignUpFields.BusinessRegNo;
import com.swiftdrop.identity.internal.dto.SignUpFields.City;
import com.swiftdrop.identity.internal.dto.SignUpFields.EmailAddress;
import com.swiftdrop.identity.internal.dto.SignUpFields.ParcelsRange;
import com.swiftdrop.identity.internal.dto.SignUpFields.Password;
import com.swiftdrop.identity.internal.dto.SignUpFields.PersonName;
import com.swiftdrop.identity.internal.dto.SignUpFields.PickupDistrict;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;
import com.swiftdrop.shared.validation.SriLankanMobile;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of POST /auth/register. {@code accountType} picks the shape: CUSTOMER or MERCHANT.
 * Validation messages are the ones in the API.md table (see {@link SignUpFields}), so the frontend can show them as-is.
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

    String phone();

    String email();

    String password();

    @Schema(name = "CustomerRegisterRequest")
    record Customer(
            @Schema(example = "Ayesha Fernando") @PersonName String fullName,
            @Schema(example = "077 123 4567") @SriLankanMobile String phone,
            @Schema(example = "ayesha@example.com") @EmailAddress String email,
            @Schema(example = "secret123") @Password String password,
            @Schema(example = "true") @AcceptTerms Boolean acceptTerms) implements RegisterRequest {

        @Override
        public String toString() {
            return "RegisterRequest.Customer[]";
        }
    }

    @Schema(name = "MerchantRegisterRequest")
    record Merchant(
            @Schema(example = "Lanka Crafts") @BusinessName String businessName,
            @Schema(example = "PV 00012345", nullable = true) @BusinessRegNo String businessRegNo,
            @ParcelsRange ParcelsPerMonth parcelsPerMonth,
            @Schema(example = "Nimal Perera") @PersonName String contactPerson,
            @Schema(example = "0712345678") @SriLankanMobile String phone,
            @Schema(example = "orders@lankacrafts.lk") @EmailAddress String email,
            @Schema(example = "COLOMBO") @PickupDistrict District pickupDistrict,
            @Schema(example = "Nugegoda") @City String city,
            @Schema(example = "secret123") @Password String password,
            @Schema(example = "true") @AcceptTerms Boolean acceptTerms) implements RegisterRequest {

        @Override
        public String toString() {
            return "RegisterRequest.Merchant[]";
        }
    }
}
