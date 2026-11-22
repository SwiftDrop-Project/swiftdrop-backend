package com.swiftdrop.customer.internal.application;

import com.swiftdrop.customer.CustomerApi;
import com.swiftdrop.customer.CustomerSummary;
import com.swiftdrop.customer.PaymentMethod;
import com.swiftdrop.customer.internal.domain.CustomerProfile;
import com.swiftdrop.customer.internal.dto.CustomerProfileResponse;
import com.swiftdrop.customer.internal.dto.UpdatePersonalDetailsRequest;
import com.swiftdrop.customer.internal.dto.UpdatePreferencesRequest;
import com.swiftdrop.customer.internal.persistence.CustomerProfileRepository;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.validation.PhoneNumberNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CustomerProfileService implements CustomerApi {

    private final CustomerProfileRepository profileRepository;
    private final IdentityApi identityApi;

    public CustomerProfileService(CustomerProfileRepository profileRepository, IdentityApi identityApi) {
        this.profileRepository = profileRepository;
        this.identityApi = identityApi;
    }

    @Override
    public Optional<CustomerSummary> findByUserId(UUID userId) {
        Optional<UserSummary> userOpt = identityApi.findById(userId);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }
        UserSummary user = userOpt.get();
        CustomerProfile profile = profileRepository.findById(userId)
                .orElseGet(() -> new CustomerProfile(userId));
        return Optional.of(new CustomerSummary(
                userId,
                user.fullName(),
                user.email(),
                user.phone(),
                profile.getDefaultPickupAddressId(),
                profile.getPreferredPaymentMethod(),
                user.createdAt()
        ));
    }

    @Transactional
    public CustomerProfileResponse getProfile(UUID userId) {
        UserSummary user = identityApi.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));

        CustomerProfile profile = profileRepository.findById(userId)
                .orElseGet(() -> profileRepository.save(new CustomerProfile(userId)));

        long totalDeliveries = 0;
        long savedAddressesCount = 0;

        return new CustomerProfileResponse(
                user.id(),
                user.fullName(),
                user.email(),
                user.phone(),
                user.createdAt(),
                totalDeliveries,
                savedAddressesCount,
                profile.getDefaultPickupAddressId(),
                profile.getPreferredPaymentMethod()
        );
    }

    @Transactional
    public CustomerProfileResponse updatePersonalDetails(UUID userId, UpdatePersonalDetailsRequest request) {
        String normalizedPhone = PhoneNumberNormalizer.normalize(request.phone())
                .orElseThrow(() -> new IllegalArgumentException("phone was not validated"));
        UserSummary updatedUser = identityApi.updateContact(userId, request.fullName(), normalizedPhone);

        CustomerProfile profile = profileRepository.findById(userId)
                .orElseGet(() -> profileRepository.save(new CustomerProfile(userId)));

        return new CustomerProfileResponse(
                updatedUser.id(),
                updatedUser.fullName(),
                updatedUser.email(),
                updatedUser.phone(),
                updatedUser.createdAt(),
                0,
                0,
                profile.getDefaultPickupAddressId(),
                profile.getPreferredPaymentMethod()
        );
    }

    @Transactional
    public CustomerProfileResponse updatePreferences(UUID userId, UpdatePreferencesRequest request) {
        UserSummary user = identityApi.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));

        CustomerProfile profile = profileRepository.findById(userId)
                .orElseGet(() -> new CustomerProfile(userId));

        profile.updatePreferences(request.defaultPickupAddressId(), request.preferredPaymentMethod());
        CustomerProfile saved = profileRepository.save(profile);

        return new CustomerProfileResponse(
                user.id(),
                user.fullName(),
                user.email(),
                user.phone(),
                user.createdAt(),
                0,
                0,
                saved.getDefaultPickupAddressId(),
                saved.getPreferredPaymentMethod()
        );
    }
}
