package com.swiftdrop.notifications.internal.persistence;

import com.swiftdrop.notifications.internal.domain.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmailLogRepository extends JpaRepository<EmailLog, UUID> {
}
