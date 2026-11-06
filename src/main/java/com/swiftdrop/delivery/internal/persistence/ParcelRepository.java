package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.delivery.internal.domain.Parcel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ParcelRepository extends JpaRepository<Parcel, UUID> {

    /** DeliveryApi.findByTrackingNumber. The caller has already normalised the number (no spaces, upper case). */
    Optional<Parcel> findByTrackingNumber(String trackingNumber);

    /** create(): a new tracking number must not be taken yet. */
    boolean existsByTrackingNumber(String trackingNumber);

    /** create(): a merchant's retry with the same order ref returns the first parcel instead of a new one. */
    Optional<Parcel> findByMerchantIdAndMerchantOrderRef(UUID merchantId, String merchantOrderRef);
}
