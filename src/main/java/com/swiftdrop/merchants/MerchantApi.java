package com.swiftdrop.merchants;

import java.util.Optional;
import java.util.UUID;

/** What other modules may ask the merchants module. Merchants are identified by their user id. */
public interface MerchantApi {

    /** The merchant's business profile, if the user has one. */
    Optional<MerchantSummary> findByUserId(UUID userId);
}
