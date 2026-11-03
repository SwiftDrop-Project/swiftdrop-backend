package com.swiftdrop.identity.internal.validation;

import com.swiftdrop.identity.internal.dto.SignUpFields;
import com.swiftdrop.identity.internal.dto.SignUpFields.DeliverableEmail;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Optional;

/**
 * Checks {@link DeliverableEmail}. Created by Spring's validator factory, so the checker is injected. Only checks
 * syntactically valid addresses: {@code @EmailAddress} reports the others, so a field never gets two errors.
 */
public class DeliverableEmailValidator implements ConstraintValidator<DeliverableEmail, String> {

    private final EmailDomainChecker checker;

    public DeliverableEmailValidator(EmailDomainChecker checker) {
        this.checker = checker;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || !SignUpFields.isWellFormedEmail(value)) {
            return true;
        }
        Optional<String> problem = checker.problemWith(value.trim());
        if (problem.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(problem.get()).addConstraintViolation();
        return false;
    }
}
