package com.swiftdrop.merchants.internal.application;

import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.MerchantProfiles;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.merchants.ApprovalStatus;
import com.swiftdrop.merchants.MerchantApi;
import com.swiftdrop.merchants.MerchantSummary;
import com.swiftdrop.merchants.internal.application.MerchantViews.MerchantAccountView;
import com.swiftdrop.merchants.internal.application.MerchantViews.MerchantApplicationView;
import com.swiftdrop.merchants.internal.application.MerchantViews.MerchantProfileView;
import com.swiftdrop.merchants.internal.domain.MerchantProfile;
import com.swiftdrop.merchants.internal.persistence.MerchantProfileRepository;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Merchant profiles and the admin approval workflow. Implements {@link MerchantApi} for other modules and
 * {@link MerchantProfiles} for identity's merchant sign-up. Users are read and changed only through {@link IdentityApi}.
 */
@Service
@Transactional(readOnly = true)
public class MerchantService implements MerchantApi, MerchantProfiles {

    private final MerchantProfileRepository profiles;
    private final IdentityApi identity;

    public MerchantService(MerchantProfileRepository profiles, IdentityApi identity) {
        this.profiles = profiles;
        this.identity = identity;
    }

    /** Called by identity inside its sign-up transaction, right after the MERCHANT user was created or completed. */
    @Override
    @Transactional
    public Profile create(UUID userId, SignUp signUp) {
        MerchantProfile profile = profiles.save(new MerchantProfile(userId, signUp.businessName().trim(),
                blankToNull(signUp.businessRegNo()), signUp.parcelsPerMonth(), signUp.pickupDistrict(),
                signUp.city().trim()));
        return toProfile(profile);
    }

    @Override
    public Optional<Profile> find(UUID userId) {
        return profiles.findById(userId).map(MerchantService::toProfile);
    }

    @Override
    public Optional<MerchantSummary> findByUserId(UUID userId) {
        return profiles.findById(userId).map(p -> new MerchantSummary(p.getUserId(), p.getBusinessName(),
                p.getPickupDistrict(), p.getCity(), p.getParcelsPerMonth(), p.getApprovalStatus()));
    }

    /** Applications with the given status, oldest first. */
    public List<MerchantApplicationView> listApplications(ApprovalStatus status) {
        List<MerchantProfile> matching = profiles.findAllByApprovalStatus(status);
        Map<UUID, UserSummary> usersById = identity.findAllById(matching.stream().map(MerchantProfile::getUserId).toList())
                .stream().collect(Collectors.toMap(UserSummary::id, Function.identity()));
        return matching.stream()
                .filter(profile -> usersById.containsKey(profile.getUserId()))
                .map(profile -> {
                    UserSummary user = usersById.get(profile.getUserId());
                    return new MerchantApplicationView(profile.getUserId(), profile.getBusinessName(), user.fullName(),
                            user.email(), user.phone(), profile.getPickupDistrict(), profile.getCity(),
                            profile.getParcelsPerMonth(), profile.getApprovalStatus(), user.createdAt());
                })
                .sorted(Comparator.comparing(MerchantApplicationView::createdAt))
                .toList();
    }

    /** Approves the application; the merchant can log in afterwards. */
    @Transactional
    public MerchantAccountView approve(UUID merchantUserId, UUID adminId) {
        MerchantProfile profile = getProfile(merchantUserId);
        profile.approve(adminId);
        UserSummary user = identity.changeStatus(merchantUserId, UserStatus.ACTIVE);
        return new MerchantAccountView(user, toView(profiles.saveAndFlush(profile)));
    }

    /** Rejects the application; login then returns ACCOUNT_REJECTED. */
    @Transactional
    public MerchantAccountView reject(UUID merchantUserId, UUID adminId) {
        MerchantProfile profile = getProfile(merchantUserId);
        profile.reject(adminId);
        UserSummary user = identity.changeStatus(merchantUserId, UserStatus.REJECTED);
        return new MerchantAccountView(user, toView(profiles.saveAndFlush(profile)));
    }

    /**
     * Creates an approved demo merchant (user, profile, approval) in one transaction. {@code normalizedPhone} must
     * already be in +947XXXXXXXX form. Used by {@link DemoMerchantSeeder}.
     */
    @Transactional
    public void seedApprovedMerchant(String contactPerson, String email, String normalizedPhone, String rawPassword,
                                     SignUp business, UUID adminId) {
        UserSummary user = identity.registerUser(contactPerson, email, normalizedPhone, rawPassword, Role.MERCHANT,
                UserStatus.PENDING_APPROVAL);
        create(user.id(), business);
        approve(user.id(), adminId);
    }

    private MerchantProfile getProfile(UUID userId) {
        return profiles.findById(userId).orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Profile toProfile(MerchantProfile profile) {
        return new Profile(profile.getBusinessName(), profile.getPickupDistrict(), profile.getCity(),
                profile.getApprovalStatus().name());
    }

    private static MerchantProfileView toView(MerchantProfile profile) {
        return new MerchantProfileView(profile.getUserId(), profile.getBusinessName(), profile.getBusinessRegNo(),
                profile.getParcelsPerMonth(), profile.getPickupDistrict(), profile.getCity(),
                profile.getApprovalStatus(), profile.getReviewedAt(), profile.getReviewedBy());
    }
}
