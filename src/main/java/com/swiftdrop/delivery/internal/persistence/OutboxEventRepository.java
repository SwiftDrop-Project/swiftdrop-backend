package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.delivery.internal.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** For now delivery only saves events here. The relay's query comes with the DeliveryOutboxApi implementation. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
}
