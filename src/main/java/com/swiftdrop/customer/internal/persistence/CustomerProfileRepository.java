package com.swiftdrop.customer.internal.persistence;

import com.swiftdrop.customer.internal.domain.CustomerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, UUID> {
}
