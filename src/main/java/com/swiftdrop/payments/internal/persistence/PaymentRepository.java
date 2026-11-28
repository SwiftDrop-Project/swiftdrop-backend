package com.swiftdrop.payments.internal.persistence;

import com.swiftdrop.payments.internal.domain.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** The Payments page: newest booking first (the id breaks ties). */
    Page<Payment> findByCustomerIdOrderByBookedAtDescIdDesc(UUID customerId, Pageable pageable);

    List<Payment> findByCustomerId(UUID customerId);

    /** Someone else's payment is "not found". */
    Optional<Payment> findByIdAndCustomerId(UUID id, UUID customerId);

    /** The payment of one of the customer's deliveries (one per parcel). */
    Optional<Payment> findByParcelIdAndCustomerId(UUID parcelId, UUID customerId);

    /**
     * Makes the record for a parcel unless it already has one. Two requests at once (two tabs) can both try: the
     * unique parcel_id keeps one row and the other insert does nothing, instead of failing.
     */
    @Modifying
    @Query(value = """
            INSERT INTO payments.payments (id, parcel_id, customer_id, tracking_number, booked_at, method, amount,
                                           currency, status, created_at, updated_at, version)
            VALUES (:id, :parcelId, :customerId, :trackingNumber, :bookedAt, :method, :amount, :currency, :status,
                    now(), now(), 0)
            ON CONFLICT (parcel_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfMissing(@Param("id") UUID id, @Param("parcelId") UUID parcelId, @Param("customerId") UUID customerId,
                        @Param("trackingNumber") String trackingNumber, @Param("bookedAt") Instant bookedAt,
                        @Param("method") String method, @Param("amount") BigDecimal amount,
                        @Param("currency") String currency, @Param("status") String status);
}
