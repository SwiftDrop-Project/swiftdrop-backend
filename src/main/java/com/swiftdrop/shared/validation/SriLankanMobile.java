package com.swiftdrop.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Bean Validation rule: the value must be a Sri Lankan mobile number (see {@link PhoneNumberNormalizer}). */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SriLankanMobile.Validator.class)
public @interface SriLankanMobile {

    String message() default "Enter a Sri Lankan mobile number, like 077 123 4567.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<SriLankanMobile, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return PhoneNumberNormalizer.isValid(value);
        }
    }
}
