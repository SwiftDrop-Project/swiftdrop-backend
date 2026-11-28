package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.delivery.internal.domain.FeePaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeePaymentRepository extends JpaRepository<FeePaymentRecord, UUID> {

    /** The parcel's payment (at most one: uk_fee_payments_parcel_id, which is also the index for this lookup). */
    Optional<FeePaymentRecord> findByParcelId(UUID parcelId);

    /** The payments of these parcels, for lists; parcels without one are simply missing. */
    List<FeePaymentRecord> findByParcelIdIn(Collection<UUID> parcelIds);

    boolean existsByParcelId(UUID parcelId);
}
