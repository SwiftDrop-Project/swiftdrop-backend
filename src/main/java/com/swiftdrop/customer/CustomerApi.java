package com.swiftdrop.customer;

import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask the customer module. Identified by the user id (identity.users.id).
 */
public interface CustomerApi {

    /** Returns the customer summary and preferences, if the customer exists. */
    Optional<CustomerSummary> findByUserId(UUID userId);
}
