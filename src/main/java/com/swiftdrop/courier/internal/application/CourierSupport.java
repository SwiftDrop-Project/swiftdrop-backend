package com.swiftdrop.courier.internal.application;

import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;

import java.util.Locale;

/** Small helpers shared by the courier services. */
final class CourierSupport {

    /** The role name delivery records as the actor of a courier's change. */
    static final String ACTOR_ROLE = "COURIER";
    static final int MAX_PAGE_SIZE = 100;

    private CourierSupport() {
    }

    /**
     * The implementation behind the provider, or 503 SERVICE_UNAVAILABLE while the owning module (delivery, dispatch)
     * has none yet.
     */
    static <T> T required(ObjectProvider<T> provider) {
        T api = provider.getIfAvailable();
        if (api == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return api;
    }

    static PageRequest pageRequest(int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "Use a page number of 0 or more.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new FieldValidationException("size", "Use a page size from 1 to " + MAX_PAGE_SIZE + ".");
        }
        return PageRequest.of(page, size);
    }

    /** Spaces removed, upper case; null when nothing is left. */
    static String normalizeTrackingNumber(String trackingNumber) {
        if (trackingNumber == null) {
            return null;
        }
        String normalized = trackingNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
