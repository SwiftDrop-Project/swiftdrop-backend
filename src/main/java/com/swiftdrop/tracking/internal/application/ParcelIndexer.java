package com.swiftdrop.tracking.internal.application;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.tracking.internal.persistence.ParcelIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes a parcel's latest status to tracking.parcel_index (the admin's all-parcels list). Runs in its own
 * transaction, and a failure is only logged: the index must never break a live update or a lookup.
 */
@Service
public class ParcelIndexer {

    private static final Logger log = LoggerFactory.getLogger(ParcelIndexer.class);

    private final ParcelIndexRepository index;
    private final TransactionTemplate newTransaction;

    public ParcelIndexer(ParcelIndexRepository index, PlatformTransactionManager transactions) {
        this.index = index;
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void index(DeliverySnapshot parcel) {
        try {
            newTransaction.executeWithoutResult(status -> index.upsert(parcel.id(), parcel.trackingNumber(),
                    parcel.status().name(),
                    city(parcel.pickup()), district(parcel.pickup()),
                    city(parcel.recipient()), district(parcel.recipient()),
                    parcel.currentBranch() == null ? null : parcel.currentBranch().name(),
                    parcel.failureReason() == null ? null : parcel.failureReason().name(),
                    parcel.createdAt(), parcel.updatedAt(), parcel.version()));
        } catch (RuntimeException e) {
            log.warn("Couldn't index parcel {}: {}", parcel.trackingNumber(), e.getClass().getSimpleName());
        }
    }

    private static String city(DeliveryContact contact) {
        return contact == null ? null : contact.city();
    }

    private static String district(DeliveryContact contact) {
        return contact == null || contact.district() == null ? null : contact.district().name();
    }
}
