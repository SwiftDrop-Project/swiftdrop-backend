package com.swiftdrop.delivery;

import java.util.Objects;
import java.util.UUID;

/**
 * Input for {@link DeliveryApi#recordFeeCollection}: a courier collected the parcel's delivery fee in cash. There is
 * no amount: delivery takes the parcel's own fee and currency, so a caller can never choose what was paid.
 *
 * <p>Which courier may collect it (their open task, the right step) is checked by the caller (WP9 courier), like
 * {@link DeliveryApi#changeStatus}.
 *
 * @param courierId    the courier who collected it (an identity user id)
 * @param assignmentId the dispatch task it was collected in
 */
public record RecordFeeCollectionCommand(UUID parcelId, UUID courierId, UUID assignmentId) {

    public RecordFeeCollectionCommand {
        Objects.requireNonNull(parcelId, "parcelId");
        Objects.requireNonNull(courierId, "courierId");
        Objects.requireNonNull(assignmentId, "assignmentId");
    }
}
