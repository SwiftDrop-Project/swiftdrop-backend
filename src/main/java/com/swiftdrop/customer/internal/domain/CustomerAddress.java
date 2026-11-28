package com.swiftdrop.customer.internal.domain;

import com.swiftdrop.shared.reference.District;
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
import java.util.Objects;
import java.util.UUID;

/**
 * One of a customer's saved pickup or drop-off addresses (customer.customer_addresses). Whether it is the default
 * pickup address is kept on the customer's profile, not here. The phone is stored normalised (+947XXXXXXXX).
 */
@Entity
@Table(name = "customer_addresses", schema = "customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerAddress implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "label", nullable = false, length = 40)
    private String label;

    @Column(name = "contact_name", nullable = false, length = 100)
    private String contactName;

    @Column(name = "phone", nullable = false, length = 16)
    private String phone;

    @Column(name = "address_line", nullable = false, length = 200)
    private String addressLine;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "district", nullable = false, length = 30)
    private District district;

    @Column(name = "postal_code", nullable = false, length = 5)
    private String postalCode;

    @Column(name = "landmark", length = 150)
    private String landmark;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    public CustomerAddress(UUID customerId, Details details) {
        this.id = UUID.randomUUID();
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        apply(details);
    }

    /** Replaces every field the customer can edit. */
    public void update(Details details) {
        apply(details);
    }

    private void apply(Details details) {
        this.label = details.label();
        this.contactName = details.contactName();
        this.phone = details.phone();
        this.addressLine = details.addressLine();
        this.city = details.city();
        this.district = details.district();
        this.postalCode = details.postalCode();
        this.landmark = details.landmark();
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

    /**
     * The editable fields, already trimmed and with the phone normalised. {@code landmark} is null when not given.
     */
    public record Details(String label, String contactName, String phone, String addressLine, String city,
                          District district, String postalCode, String landmark) {

        /** Contacts are personal data: keep them out of logs. */
        @Override
        public String toString() {
            return "Details[label=" + label + ", district=" + district + "]";
        }
    }
}
