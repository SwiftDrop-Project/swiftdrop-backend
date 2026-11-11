package com.swiftdrop.dispatch.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.dispatch.internal.persistence.PickupQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the pickup queue (dispatch.pickup_queue) in step with delivery's {@link ParcelStatusChanged} events.
 *
 * <p>Events arrive at least once and maybe out of order, so handling is idempotent: a CREATED event adds the parcel
 * only while delivery still says it is CREATED (a late CREATED event after the pickup adds nothing), and any other
 * event removes it unless the row is newer than the event.
 */
@Service
public class ParcelEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ParcelEventHandler.class);

    private final DeliveryApi deliveries;
    private final PickupQueueRepository pickupQueue;

    public ParcelEventHandler(DeliveryApi deliveries, PickupQueueRepository pickupQueue) {
        this.deliveries = deliveries;
        this.pickupQueue = pickupQueue;
    }

    @Transactional
    public void handle(ParcelStatusChanged event) {
        if (event.toStatus() == ParcelStatus.CREATED) {
            deliveries.findById(event.parcelId())
                    .filter(parcel -> parcel.status() == ParcelStatus.CREATED)
                    .ifPresent(parcel -> pickupQueue.insertIfAbsent(parcel.id(), parcel.trackingNumber(),
                            parcel.pickup().district().name(), parcel.version(), parcel.createdAt()));
        } else {
            pickupQueue.deleteUpTo(event.parcelId(), event.parcelVersion());
        }
        log.debug("Pickup queue updated for parcel {} ({})", event.parcelId(), event.toStatus());
    }
}
