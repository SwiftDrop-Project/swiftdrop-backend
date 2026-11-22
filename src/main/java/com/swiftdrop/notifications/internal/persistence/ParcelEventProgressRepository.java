
package com.swiftdrop.notifications.internal.persistence;

import com.swiftdrop.notifications.internal.domain.ParcelEventProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ParcelEventProgressRepository
        extends JpaRepository<ParcelEventProgress, UUID> {
}
