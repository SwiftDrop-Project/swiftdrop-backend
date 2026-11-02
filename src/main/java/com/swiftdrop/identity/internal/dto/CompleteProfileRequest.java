package com.swiftdrop.identity.internal.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.swiftdrop.identity.internal.dto.SignUpFields.AcceptTerms;
import com.swiftdrop.identity.internal.dto.SignUpFields.BusinessName;
import com.swiftdrop.identity.internal.dto.SignUpFields.BusinessRegNo;
import com.swiftdrop.identity.internal.dto.SignUpFields.City;
import com.swiftdrop.identity.internal.dto.SignUpFields.ParcelsRange;
import com.swiftdrop.identity.internal.dto.SignUpFields.PersonName;
import com.swiftdrop.identity.internal.dto.SignUpFields.PickupDistrict;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;
import com.swiftdrop.shared.validation.SriLankanMobile;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of POST /auth/complete-profile, sent after a Google sign-up. Same fields and rules as
 * {@link RegisterRequest} without email and password (Google provided the email; there is no password).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "accountType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CompleteProfileRequest.Customer.class, name = "CUSTOMER"),
        @JsonSubTypes.Type(value = CompleteProfileRequest.Merchant.class, name = "MERCHANT")
})
@Schema(oneOf = {CompleteProfileRequest.Customer.class, CompleteProfileRequest.Merchant.class},
        discriminatorProperty = "accountType",
        discriminatorMapping = {
                @DiscriminatorMapping(value = "CUSTOMER", schema = CompleteProfileRequest.Customer.class),
                @DiscriminatorMapping(value = "MERCHANT", schema = CompleteProfileRequest.Merchant.class)
        })
public sealed interface CompleteProfileRequest
        permits CompleteProfileRequest.Customer, CompleteProfileRequest.Merchant {

    @Schema(name = "CustomerCompleteProfileRequest")
    record Customer(
            @Schema(example = "077 123 4567") @SriLankanMobile String phone,
            @Schema(example = "true") @AcceptTerms Boolean acceptTerms) implements CompleteProfileRequest {

        @Override
        public String toString() {
            return "CompleteProfileRequest.Customer[]";
        }
    }

    @Schema(name = "MerchantCompleteProfileRequest")
    record Merchant(
            @Schema(example = "Lanka Crafts") @BusinessName String businessName,
            @Schema(example = "PV 00012345", nullable = true) @BusinessRegNo String businessRegNo,
            @ParcelsRange ParcelsPerMonth parcelsPerMonth,
            @Schema(example = "Nimal Perera") @PersonName String contactPerson,
            @Schema(example = "0712345678") @SriLankanMobile String phone,
            @Schema(example = "COLOMBO") @PickupDistrict District pickupDistrict,
            @Schema(example = "Nugegoda") @City String city,
            @Schema(example = "true") @AcceptTerms Boolean acceptTerms) implements CompleteProfileRequest {

        @Override
        public String toString() {
            return "CompleteProfileRequest.Merchant[]";
        }
    }
}
