package com.swiftdrop.infrastructure.internal.web;

import com.swiftdrop.shared.error.ApiError;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Turns every exception into the API.md error shape.
 * Never echoes request values, stack traces or exception messages back to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Messages for fields whose JSON value could not be read at all (bad enum value, wrong type). */
    private static final Map<String, String> UNREADABLE_FIELD_MESSAGES = Map.of(
            "accountType", "Select an account type.",
            "pickupDistrict", "Select a district.",
            "parcelsPerMonth", "Select a range.",
            "acceptTerms", "Accept the terms to continue.");

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex) {
        return respond(ApiError.of(ex.code(), ex.getMessage()));
    }

    /** A {@code @PreAuthorize} check failed: logged in, wrong role (URL rules answer before this in most cases). */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return respond(ApiError.of(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return respond(ApiError.validation(fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (ex.getCause() instanceof JacksonException jackson) {
            String field = jackson.getPath().stream()
                    .map(JacksonException.Reference::getPropertyName)
                    .filter(name -> name != null)
                    .reduce((first, last) -> last)
                    .orElse(null);
            if (field == null && jackson.getClass().getSimpleName().equals("InvalidTypeIdException")) {
                field = "accountType";
            }
            if (field != null) {
                fieldErrors.put(field, UNREADABLE_FIELD_MESSAGES.getOrDefault(field, "This value is not valid."));
            }
        }
        return respond(ApiError.validation(fieldErrors));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> handleBadParameter(Exception ex) {
        return respond(ApiError.validation(Map.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        // Backstop for two registrations racing past the up-front duplicate check.
        String constraint = constraintName(ex);
        if (constraint.contains("uk_users_email")) {
            return respond(ApiError.of(ErrorCode.EMAIL_ALREADY_REGISTERED));
        }
        if (constraint.contains("uk_users_phone")) {
            return respond(ApiError.of(ErrorCode.PHONE_ALREADY_REGISTERED));
        }
        return handleUnexpected(ex, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return respond(ApiError.of(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return respond(ApiError.of(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return respond(ApiError.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Exception messages can contain user data (e.g. SQL values), so only the type and location
        // are logged at ERROR. The full stack trace is available at DEBUG for local troubleshooting.
        String errorId = UUID.randomUUID().toString();
        StackTraceElement origin = ex.getStackTrace().length > 0 ? ex.getStackTrace()[0] : null;
        log.error("Unexpected error {} on {} {}: {} at {}", errorId, request.getMethod(), request.getRequestURI(),
                ex.getClass().getName(), origin);
        log.debug("Stack trace for error {}", errorId, ex);
        return respond(ApiError.of(ErrorCode.INTERNAL_ERROR));
    }

    private static ResponseEntity<ApiError> respond(ApiError body) {
        return ResponseEntity.status(body.status()).body(body);
    }

    private static String constraintName(DataIntegrityViolationException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve && cve.getConstraintName() != null) {
                return cve.getConstraintName().toLowerCase(Locale.ROOT);
            }
        }
        String message = ex.getMostSpecificCause().getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }
}
