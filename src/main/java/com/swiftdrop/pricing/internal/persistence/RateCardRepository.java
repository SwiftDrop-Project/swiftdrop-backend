package com.swiftdrop.pricing.internal.persistence;

import com.swiftdrop.pricing.internal.domain.RateCardEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RateCardRepository extends JpaRepository<RateCardEntity, UUID> {

    /** The rate card quotes use now; uk_rate_cards_one_active allows at most one. */
    Optional<RateCardEntity> findByActiveTrue();

    /** A booking's rate card, to work its quote out again later. */
    Optional<RateCardEntity> findByVersion(int version);
}
