package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.internal.validation.DeliverableEmailValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The validation rules of the sign-up forms (API.md "Validation rules"), one annotation per field type.
 * Used by both POST /auth/register and POST /auth/complete-profile, so the two always agree.
 * Each reports a single error with the message from API.md. The phone rule is
 * {@link com.swiftdrop.shared.validation.SriLankanMobile}.
 */
public final class SignUpFields {

    public static final String NAME_MESSAGE = "Enter your full name.";
    public static final String EMAIL_MESSAGE = "Enter a full email address, like name@example.com";
    public static final String PASSWORD_MESSAGE = "Use at least 8 characters, including a number.";
    public static final String BUSINESS_NAME_MESSAGE = "Enter your business name.";
    public static final String BUSINESS_REG_NO_MESSAGE = "Use at most 30 characters.";
    public static final String PARCELS_MESSAGE = "Select a range.";
    public static final String DISTRICT_MESSAGE = "Select a district.";
    public static final String CITY_MESSAGE = "Enter your city or town.";
    public static final String TERMS_MESSAGE = "Accept the terms to continue.";
    public static final String CODE_MESSAGE = "Enter the 6-digit code.";
    public static final String IDENTIFIER_MESSAGE = "Enter your mobile number or email.";
    /** 8-72 characters with at least one letter and one digit. */
    static final String PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$";
    /** Requires a dot in the domain part, e.g. name@example.com. */
    static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
    private static final java.util.regex.Pattern EMAIL_REGEX = java.util.regex.Pattern.compile(EMAIL_PATTERN);

    private SignUpFields() {
    }

    /** {@code fullName} and {@code contactPerson}: 2-100 characters. */
    @NotBlank
    @Size(min = 2, max = 100)
    @ReportAsSingleViolation
    @Constraint(validatedBy = {})
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface PersonName {
        String message() default NAME_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** Valid email with a dot in the domain, max 254 characters. */
    @NotBlank
    @Size(max = 254)
    @Email(regexp = EMAIL_PATTERN)
    @ReportAsSingleViolation
    @Constraint(validatedBy = {})
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface EmailAddress {
        String message() default EMAIL_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /**
     * Sign-up only: the email's domain must not be a disposable-inbox service and must have MX records (DNS failures
     * are allowed). Messages come from {@code EmailDomainChecker}. Checked only when the address is well-formed.
     */
    @Constraint(validatedBy = DeliverableEmailValidator.class)
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface DeliverableEmail {
        String message() default EMAIL_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** Whether {@code email} passes {@link EmailAddress} (so the domain checks only run on well-formed addresses). */
    public static boolean isWellFormedEmail(String email) {
        String value = email.trim();
        return !value.isEmpty() && email.length() <= 254 && EMAIL_REGEX.matcher(value).matches();
    }

    /** A one-time code from an email: exactly 6 digits. */
    @NotNull
    @Pattern(regexp = "^\\d{6}$")
    @ReportAsSingleViolation
    @Constraint(validatedBy = {})
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface OneTimeCode {
        String message() default CODE_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** 8-72 characters, at least one letter and one number. */
    @NotNull
    @Pattern(regexp = PASSWORD_PATTERN)
    @ReportAsSingleViolation
    @Constraint(validatedBy = {})
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface Password {
        String message() default PASSWORD_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** 2-120 characters. */
    @NotBlank
    @Size(min = 2, max = 120)
    @ReportAsSingleViolation
    @Constraint(validatedBy = {})
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface BusinessName {
        String message() default BUSINESS_NAME_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** Optional, max 30 characters. */
    @Size(max = 30)
    @ReportAsSingleViolation
    @Constraint(validatedBy = {})
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface BusinessRegNo {
        String message() default BUSINESS_REG_NO_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** Required {@code parcelsPerMonth}. Unknown values are rejected earlier by GlobalExceptionHandler. */
    @NotNull
    @ReportAsSingleViolation
    @Constraint(validatedBy = {})
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface ParcelsRange {
        String message() default PARCELS_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** Required {@code pickupDistrict}. Unknown codes are rejected earlier by GlobalExceptionHandler. */
    @NotNull
    @ReportAsSingleViolation
    @Constraint(validatedBy = {})
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface PickupDistrict {
        String message() default DISTRICT_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** 2-80 characters. */
    @NotBlank
    @Size(min = 2, max = 80)
    @ReportAsSingleViolation
    @Constraint(validatedBy = {})
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface City {
        String message() default CITY_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** {@code acceptTerms} must be {@code true}. */
    @NotNull
    @AssertTrue
    @ReportAsSingleViolation
    @Constraint(validatedBy = {})
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    @Documented
    public @interface AcceptTerms {
        String message() default TERMS_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }
}
