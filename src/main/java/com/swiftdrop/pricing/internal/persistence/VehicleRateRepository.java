package com.swiftdrop.pricing.internal.persistence;

import com.swiftdrop.pricing.internal.domain.VehicleRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VehicleRateRepository extends JpaRepository<VehicleRateEntity, UUID> {

    /** A rate card's vehicles, smallest first. */
    List<VehicleRateEntity> findByRateCardIdOrderByCheckOrder(UUID rateCardId);
}
