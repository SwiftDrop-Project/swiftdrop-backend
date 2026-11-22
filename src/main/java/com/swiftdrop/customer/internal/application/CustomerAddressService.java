package com.swiftdrop.customer.internal.application;

import com.swiftdrop.customer.internal.domain.CustomerAddress;
import com.swiftdrop.customer.internal.domain.CustomerProfile;
import com.swiftdrop.customer.internal.dto.CustomerAddressRequest;
import com.swiftdrop.customer.internal.dto.CustomerAddressResponse;
import com.swiftdrop.customer.internal.persistence.CustomerAddressRepository;
import com.swiftdrop.customer.internal.persistence.CustomerProfileRepository;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.validation.PhoneNumberNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The customer's saved addresses (the Addresses page). Every call is for the logged-in customer: someone else's
 * address answers 404 NOT_FOUND, the same as an unknown id, so ids can't be probed. The default pickup address is the
 * profile's {@code defaultPickupAddressId}, so there is at most one.
 */
@Service
@Transactional(readOnly = true)
public class CustomerAddressService {

    /** The most addresses one customer can save. */
    static final int MAX_ADDRESSES = 20;

    private final CustomerAddressRepository addresses;
    private final CustomerProfileRepository profiles;

    public CustomerAddressService(CustomerAddressRepository addresses, CustomerProfileRepository profiles) {
        this.addresses = addresses;
        this.profiles = profiles;
    }

    /** The default pickup address first, then the rest oldest first (the order they were saved). */
    public List<CustomerAddressResponse> list(UUID customerId) {
        UUID defaultId = defaultAddressId(customerId);
        return addresses.findByCustomerIdOrderByCreatedAtAscIdAsc(customerId).stream()
                .sorted(Comparator.comparing((CustomerAddress address) -> !address.getId().equals(defaultId)))
                .map(address -> CustomerAddressResponse.from(address, address.getId().equals(defaultId)))
                .toList();
    }

    @Transactional
    public CustomerAddressResponse create(UUID customerId, CustomerAddressRequest request) {
        if (addresses.countByCustomerId(customerId) >= MAX_ADDRESSES) {
            throw new ConflictException(ErrorCode.ADDRESS_LIMIT_REACHED);
        }
        CustomerAddress address = addresses.save(new CustomerAddress(customerId, details(request)));
        if (Boolean.TRUE.equals(request.useAsDefault())) {
            setDefault(customerId, address.getId());
        }
        return CustomerAddressResponse.from(address, address.getId().equals(defaultAddressId(customerId)));
    }

    @Transactional
    public CustomerAddressResponse update(UUID customerId, UUID addressId, CustomerAddressRequest request) {
        CustomerAddress address = own(customerId, addressId);
        address.update(details(request));
        if (Boolean.TRUE.equals(request.useAsDefault())) {
            setDefault(customerId, addressId);
        } else if (Boolean.FALSE.equals(request.useAsDefault()) && addressId.equals(defaultAddressId(customerId))) {
            setDefault(customerId, null);
        }
        return CustomerAddressResponse.from(address, addressId.equals(defaultAddressId(customerId)));
    }

    @Transactional
    public CustomerAddressResponse makeDefault(UUID customerId, UUID addressId) {
        CustomerAddress address = own(customerId, addressId);
        setDefault(customerId, addressId);
        return CustomerAddressResponse.from(address, true);
    }

    /** Deleting the default pickup address leaves the customer without a default. */
    @Transactional
    public void delete(UUID customerId, UUID addressId) {
        CustomerAddress address = own(customerId, addressId);
        if (addressId.equals(defaultAddressId(customerId))) {
            setDefault(customerId, null);
        }
        addresses.delete(address);
    }

    /** True when the address exists and is this customer's (for the profile's default pickup address). */
    public boolean isOwnAddress(UUID customerId, UUID addressId) {
        return addresses.existsByIdAndCustomerId(addressId, customerId);
    }

    public long count(UUID customerId) {
        return addresses.countByCustomerId(customerId);
    }

    private CustomerAddress own(UUID customerId, UUID addressId) {
        return addresses.findByIdAndCustomerId(addressId, customerId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    private UUID defaultAddressId(UUID customerId) {
        return profiles.findById(customerId).map(CustomerProfile::getDefaultPickupAddressId).orElse(null);
    }

    /** Sets (or with null clears) the default pickup address, creating the profile on first use. */
    private void setDefault(UUID customerId, UUID addressId) {
        CustomerProfile profile = profiles.findById(customerId).orElseGet(() -> new CustomerProfile(customerId));
        if (Objects.equals(profile.getDefaultPickupAddressId(), addressId) && !profile.isNew()) {
            return;
        }
        profile.updatePreferences(addressId, null);
        profiles.save(profile);
    }

    /** Trimmed values and the stored phone form (+947XXXXXXXX); the request was already validated. */
    private static CustomerAddress.Details details(CustomerAddressRequest request) {
        String phone = PhoneNumberNormalizer.normalize(request.phone())
                .orElseThrow(() -> new IllegalArgumentException("phone was not validated"));
        String landmark = request.landmark() == null || request.landmark().isBlank() ? null : request.landmark().trim();
        return new CustomerAddress.Details(request.label().trim(), request.contactName().trim(), phone,
                request.addressLine().trim(), request.city().trim(), request.district(), request.postalCode().trim(),
                landmark);
    }
}
