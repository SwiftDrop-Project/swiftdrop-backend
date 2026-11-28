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
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The address rules without Spring: the default lives on the profile, the limit, and other customers' addresses. */
class CustomerAddressServiceTest {

    private static final UUID CUSTOMER = UUID.randomUUID();

    private final CustomerAddressRepository addresses = mock(CustomerAddressRepository.class);
    private final CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
    private final CustomerAddressService service = new CustomerAddressService(addresses, profiles);

    @Test
    void aNewAddressIsTrimmedAndItsPhoneNormalised() {
        when(addresses.save(any())).thenAnswer(call -> call.getArgument(0));
        when(profiles.findById(CUSTOMER)).thenReturn(Optional.empty());

        CustomerAddressResponse saved = service.create(CUSTOMER, request(" Home ", " 077 123 4567 ", null));

        assertThat(saved.label()).isEqualTo("Home");
        assertThat(saved.phone()).isEqualTo("+94771234567");
        assertThat(saved.landmark()).isNull();
        assertThat(saved.isDefault()).isFalse();
        verify(profiles, never()).save(any());
    }

    @Test
    void useAsDefaultStoresTheAddressOnTheProfile() {
        when(addresses.save(any())).thenAnswer(call -> call.getArgument(0));
        CustomerProfile profile = new CustomerProfile(CUSTOMER);
        when(profiles.findById(CUSTOMER)).thenReturn(Optional.of(profile));

        CustomerAddressResponse saved = service.create(CUSTOMER, request("Home", "0771234567", true));

        assertThat(saved.isDefault()).isTrue();
        assertThat(profile.getDefaultPickupAddressId()).isEqualTo(saved.id());
        verify(profiles).save(profile);
    }

    @Test
    void theTwentyFirstAddressIsRefused() {
        when(addresses.countByCustomerId(CUSTOMER)).thenReturn((long) CustomerAddressService.MAX_ADDRESSES);

        assertThatThrownBy(() -> service.create(CUSTOMER, request("Home", "0771234567", false)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ADDRESS_LIMIT_REACHED));
        verify(addresses, never()).save(any());
    }

    @Test
    void theDefaultComesFirstThenTheRestInSavedOrder() {
        CustomerAddress home = address("Home");
        CustomerAddress shop = address("Shop");
        CustomerAddress parents = address("Parents");
        when(addresses.findByCustomerIdOrderByCreatedAtAscIdAsc(CUSTOMER)).thenReturn(List.of(home, shop, parents));
        when(profiles.findById(CUSTOMER)).thenReturn(Optional.of(
                new CustomerProfile(CUSTOMER, shop.getId(), null)));

        List<CustomerAddressResponse> listed = service.list(CUSTOMER);

        assertThat(listed).extracting(CustomerAddressResponse::label).containsExactly("Shop", "Home", "Parents");
        assertThat(listed).extracting(CustomerAddressResponse::isDefault).containsExactly(true, false, false);
    }

    @Test
    void deletingTheDefaultClearsItFromTheProfile() {
        CustomerAddress home = address("Home");
        CustomerProfile profile = new CustomerProfile(CUSTOMER, home.getId(), null);
        when(addresses.findByIdAndCustomerId(home.getId(), CUSTOMER)).thenReturn(Optional.of(home));
        when(profiles.findById(CUSTOMER)).thenReturn(Optional.of(profile));

        service.delete(CUSTOMER, home.getId());

        ArgumentCaptor<CustomerProfile> saved = ArgumentCaptor.forClass(CustomerProfile.class);
        verify(profiles).save(saved.capture());
        assertThat(saved.getValue().getDefaultPickupAddressId()).isNull();
        verify(addresses).delete(home);
    }

    @Test
    void someoneElsesAddressIsNotFoundAndNothingChanges() {
        UUID theirs = UUID.randomUUID();
        when(addresses.findByIdAndCustomerId(theirs, CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(CUSTOMER, theirs, request("Home", "0771234567", true)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.makeDefault(CUSTOMER, theirs)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(CUSTOMER, theirs)).isInstanceOf(NotFoundException.class);
        verify(profiles, never()).save(any());
        verify(addresses, never()).delete(any());
    }

    private static CustomerAddressRequest request(String label, String phone, Boolean useAsDefault) {
        return new CustomerAddressRequest(label, "Nimali Perera", phone, "24 Temple Road", "Nugegoda",
                District.COLOMBO, "10250", " ", useAsDefault);
    }

    private static CustomerAddress address(String label) {
        return new CustomerAddress(CUSTOMER, new CustomerAddress.Details(label, "Nimali Perera", "+94771234567",
                "24 Temple Road", "Nugegoda", District.COLOMBO, "10250", null));
    }
}
