package com.swiftdrop.shared.error;

import org.springframework.http.HttpStatus;

/** Every error code in API.md, with its HTTP status and default message. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Some fields need attention."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Please log in to continue."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "The mobile number, email or password is incorrect."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to do this."),
    ACCOUNT_PENDING_APPROVAL(HttpStatus.FORBIDDEN,
            "Your merchant account is waiting for approval. We'll email you when it's ready."),
    ACCOUNT_REJECTED(HttpStatus.FORBIDDEN, "Your merchant application was not approved."),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "Your account has been disabled. Please contact support."),
    PROFILE_INCOMPLETE(HttpStatus.FORBIDDEN, "Finish setting up your account to continue."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN,
            "Confirm your email address to continue. Enter the 6-digit code we emailed you."),
    INVALID_CODE(HttpStatus.BAD_REQUEST, "That code isn't right. Check it and try again."),
    CODE_EXPIRED(HttpStatus.BAD_REQUEST, "This code has expired. Ask for a new one."),
    TOO_MANY_ATTEMPTS(HttpStatus.BAD_REQUEST, "Too many wrong codes. Ask for a new one."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please wait a moment and try again."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "We couldn't find what you were looking for."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "This action is not supported here."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "This email is already registered."),
    PHONE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "This mobile number is already registered."),
    PROFILE_ALREADY_COMPLETE(HttpStatus.CONFLICT, "Your account is already set up."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the request as JSON (application/json)."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Please try again.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
