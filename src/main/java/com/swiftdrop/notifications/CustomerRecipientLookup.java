
package com.swiftdrop.notifications;

import java.util.Optional;
import java.util.UUID;

/**
 * Customer recipient lookup contract for parcel notification emails.
 * Implemented by the Identity module.
 */
public interface CustomerRecipientLookup {

    Optional<CustomerRecipient> findById(UUID customerId);

    record CustomerRecipient(
            String fullName,
            String email
    ) {
    }
}
