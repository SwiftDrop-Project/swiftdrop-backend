package com.swiftdrop.customer.internal.domain;

import com.swiftdrop.customer.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_profiles", schema = "customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerProfile implements Persistable<UUID> {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "default_pickup_address_id")
    private UUID defaultPickupAddressId;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_payment_method", nullable = false, length = 30)
    private PaymentMethod preferredPaymentMethod = PaymentMethod.CASH_ON_DELIVERY;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    public CustomerProfile(UUID userId) {
        this.userId = userId;
        this.preferredPaymentMethod = PaymentMethod.CASH_ON_DELIVERY;
    }

    public CustomerProfile(UUID userId, UUID defaultPickupAddressId, PaymentMethod preferredPaymentMethod) {
        this.userId = userId;
        this.defaultPickupAddressId = defaultPickupAddressId;
        this.preferredPaymentMethod = preferredPaymentMethod != null ? preferredPaymentMethod : PaymentMethod.CASH_ON_DELIVERY;
    }

    public void updatePreferences(UUID defaultPickupAddressId, PaymentMethod preferredPaymentMethod) {
        this.defaultPickupAddressId = defaultPickupAddressId;
        if (preferredPaymentMethod != null) {
            this.preferredPaymentMethod = preferredPaymentMethod;
        }
    }

    @Override
    public UUID getId() {
        return userId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getDefaultPickupAddressId() {
        return defaultPickupAddressId;
    }

    public PaymentMethod getPreferredPaymentMethod() {
        return preferredPaymentMethod;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
