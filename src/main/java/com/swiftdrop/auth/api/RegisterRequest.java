package com.swiftdrop.auth.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.swiftdrop.auth.api.SignUpFields.AcceptTerms;
import com.swiftdrop.auth.api.SignUpFields.BusinessName;
import com.swiftdrop.auth.api.SignUpFields.BusinessRegNo;
import com.swiftdrop.auth.api.SignUpFields.City;
import com.swiftdrop.auth.api.SignUpFields.EmailAddress;
import com.swiftdrop.auth.api.SignUpFields.ParcelsRange;
import com.swiftdrop.auth.api.SignUpFields.Password;
import com.swiftdrop.auth.api.SignUpFields.PersonName;
import com.swiftdrop.auth.api.SignUpFields.PickupDistrict;
import com.swiftdrop.merchants.domain.ParcelsPerMonth;
import com.swiftdrop.shared.meta.District;
import com.swiftdrop.shared.util.SriLankanMobile;
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
