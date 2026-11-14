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
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "Change your temporary password to continue."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN,
            "Confirm your email address to continue. Enter the 6-digit code we emailed you."),
    INVALID_CODE(HttpStatus.BAD_REQUEST, "That code isn't right. Check it and try again."),
    CODE_EXPIRED(HttpStatus.BAD_REQUEST, "This code has expired. Ask for a new one."),
    TOO_MANY_ATTEMPTS(HttpStatus.BAD_REQUEST, "Too many wrong codes. Ask for a new one."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please wait a moment and try again."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "We couldn't find what you were looking for."),
    API_KEY_REVOKED(HttpStatus.UNAUTHORIZED, "This API key has been revoked."),
    MERCHANT_NOT_APPROVED(HttpStatus.FORBIDDEN, "Your merchant account is not approved yet."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "This action is not supported here."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "This email is already registered."),
    PHONE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "This mobile number is already registered."),
    PROFILE_ALREADY_COMPLETE(HttpStatus.CONFLICT, "Your account is already set up."),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT,
            "This parcel can't move to that status now. Refresh to see its latest status."),
    PARCEL_NOT_ASSIGNABLE(HttpStatus.CONFLICT, "A courier can only be assigned before the parcel is picked up."),
    /** The courier's step isn't allowed for this parcel in this assignment now (dispatch {@code ItemOutcome}). */
    TASK_STEP_NOT_ALLOWED(HttpStatus.CONFLICT,
            "This step isn't possible for this parcel now. Refresh to see your task."),
    ASSIGNMENT_CLOSED(HttpStatus.CONFLICT, "This task is already completed or was cancelled."),
    ASSIGNMENT_NOT_FINISHED(HttpStatus.CONFLICT, "Update every parcel in this task before you finish it."),
    /** Dispatch: the parcel is already in another open courier task. */
    PARCEL_ALREADY_ASSIGNED(HttpStatus.CONFLICT,
            "This parcel is already in another courier's task. Refresh to see the latest queue."),
    /** Dispatch: the parcel's status or branch doesn't suit the task (e.g. a pickup for a parcel already at a branch). */
    PARCEL_NOT_READY_FOR_TASK(HttpStatus.CONFLICT,
            "This parcel can't be added to this task now. Refresh to see its latest status."),
    /** Dispatch: the courier has already started the task, so it can't be cancelled or given to someone else. */
    ASSIGNMENT_STARTED(HttpStatus.CONFLICT, "The courier has already started this task."),
    /** Dispatch: removing the last parcel would leave an empty task; cancel the task instead. */
    ASSIGNMENT_NEEDS_PARCEL(HttpStatus.CONFLICT, "A task needs at least one parcel. Cancel the task instead."),
    /** Dispatch: someone else changed the task at the same moment. */
    ASSIGNMENT_CHANGED(HttpStatus.CONFLICT, "This task was just changed by someone else. Refresh and try again."),
    COURIER_NOT_ASSIGNABLE(HttpStatus.CONFLICT, "This courier is not active and cannot be assigned parcels."),
    ACCOUNT_REMOVED(HttpStatus.CONFLICT, "This account has been permanently removed and cannot be modified."),
    CANNOT_CHANGE_OWN_ACCOUNT(HttpStatus.CONFLICT, "You can't block your own account."),
    ACCOUNT_NOT_ACTIVE(HttpStatus.CONFLICT,
            "Only active accounts can be blocked. Refresh to see the account's latest status."),
    ACCOUNT_NOT_BLOCKED(HttpStatus.CONFLICT,
            "Only blocked accounts can be unblocked. Refresh to see the account's latest status."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the request as JSON (application/json)."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Please try again."),
    /** A part of SwiftDrop that another module provides is not running yet (e.g. no delivery implementation). */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "This isn't available yet. Please try again later.");

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
