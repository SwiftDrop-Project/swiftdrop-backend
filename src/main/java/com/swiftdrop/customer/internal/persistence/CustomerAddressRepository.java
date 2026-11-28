package com.swiftdrop.customer.internal.persistence;

import com.swiftdrop.customer.internal.domain.CustomerAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, UUID> {

    /** A customer's addresses, oldest first (the id breaks ties between addresses saved at the same moment). */
    List<CustomerAddress> findByCustomerIdOrderByCreatedAtAscIdAsc(UUID customerId);

    /** The address only when it belongs to this customer: someone else's address is "not found". */
    Optional<CustomerAddress> findByIdAndCustomerId(UUID id, UUID customerId);

    long countByCustomerId(UUID customerId);

    boolean existsByIdAndCustomerId(UUID id, UUID customerId);
}
