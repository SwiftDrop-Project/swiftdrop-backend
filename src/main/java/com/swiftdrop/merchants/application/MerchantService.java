package com.swiftdrop.merchants.application;

import com.swiftdrop.merchants.application.MerchantViews.BusinessDetails;
import com.swiftdrop.merchants.application.MerchantViews.MerchantAccountView;
import com.swiftdrop.merchants.application.MerchantViews.MerchantApplication;
import com.swiftdrop.merchants.application.MerchantViews.MerchantApplicationView;
import com.swiftdrop.merchants.application.MerchantViews.MerchantProfileView;
import com.swiftdrop.merchants.application.MerchantViews.NewMerchant;
import com.swiftdrop.merchants.domain.ApprovalStatus;
import com.swiftdrop.merchants.domain.MerchantProfile;
import com.swiftdrop.merchants.infrastructure.MerchantProfileRepository;
import com.swiftdrop.shared.exception.ApiException;
import com.swiftdrop.shared.exception.ErrorCode;
import com.swiftdrop.users.application.NewUser;
import com.swiftdrop.users.application.UserService;
import com.swiftdrop.users.application.UserView;
import com.swiftdrop.users.domain.Role;
import com.swiftdrop.users.domain.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Merchant sign-up (used by auth) and the admin approval workflow. */
@Service
@Transactional(readOnly = true)
public class MerchantService {

    private final MerchantProfileRepository profiles;
    private final UserService userService;

    public MerchantService(MerchantProfileRepository profiles, UserService userService) {
        this.profiles = profiles;
        this.userService = userService;
    }

    /** Creates a MERCHANT user (PENDING_APPROVAL) and its profile (PENDING) in one transaction. */
    @Transactional
    public MerchantAccountView registerMerchant(NewMerchant command) {
        UserView user = userService.register(new NewUser(command.contactPerson(), command.email(), command.phone(),
                command.rawPassword(), Role.MERCHANT, UserStatus.PENDING_APPROVAL));
        return new MerchantAccountView(user, createProfile(user.id(), command.business()));
    }

    /**
     * Turns a Google sign-up (status PROFILE_INCOMPLETE) into a merchant application: the user becomes a
     * MERCHANT with PENDING_APPROVAL, named after the contact person, and gets a PENDING profile.
     * Same errors as {@link UserService#completeProfile}.
     */
    @Transactional
    public MerchantAccountView applyAsMerchant(UUID userId, MerchantApplication command) {
        UserView user = userService.completeProfile(userId, command.contactPerson(), command.phone(), Role.MERCHANT,
                UserStatus.PENDING_APPROVAL);
        return new MerchantAccountView(user, createProfile(user.id(), command.business()));
    }

    private MerchantProfileView createProfile(UUID userId, BusinessDetails business) {
        return toView(profiles.save(new MerchantProfile(userId, business.businessName().trim(),
                blankToNull(business.businessRegNo()), business.parcelsPerMonth(), business.pickupDistrict(),
                business.city().trim())));
    }

    public Optional<MerchantProfileView> findProfile(UUID userId) {
        return profiles.findById(userId).map(MerchantService::toView);
    }

    /** Applications with the given status, oldest first. */
    public List<MerchantApplicationView> listApplications(ApprovalStatus status) {
        List<MerchantProfile> matching = profiles.findAllByApprovalStatus(status);
        Map<UUID, UserView> usersById = userService.findAllById(matching.stream().map(MerchantProfile::getUserId).toList())
                .stream().collect(Collectors.toMap(UserView::id, Function.identity()));
        return matching.stream()
                .filter(profile -> usersById.containsKey(profile.getUserId()))
                .map(profile -> {
                    UserView user = usersById.get(profile.getUserId());
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
        UserView user = userService.changeStatus(merchantUserId, UserStatus.ACTIVE);
        return new MerchantAccountView(user, toView(profiles.saveAndFlush(profile)));
    }

    /** Rejects the application; login then returns ACCOUNT_REJECTED. */
    @Transactional
    public MerchantAccountView reject(UUID merchantUserId, UUID adminId) {
        MerchantProfile profile = getProfile(merchantUserId);
        profile.reject(adminId);
        UserView user = userService.changeStatus(merchantUserId, UserStatus.REJECTED);
        return new MerchantAccountView(user, toView(profiles.saveAndFlush(profile)));
    }

    private MerchantProfile getProfile(UUID userId) {
        return profiles.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static MerchantProfileView toView(MerchantProfile profile) {
        return new MerchantProfileView(profile.getUserId(), profile.getBusinessName(), profile.getBusinessRegNo(),
                profile.getParcelsPerMonth(), profile.getPickupDistrict(), profile.getCity(),
                profile.getApprovalStatus(), profile.getReviewedAt(), profile.getReviewedBy());
    }
}
