package com.swiftdrop.identity;

import com.swiftdrop.shared.reference.District;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask the identity module. The only way to read or change users from outside identity.
 */
public interface IdentityApi {

    Optional<UserSummary> findById(UUID id);

    /** Unknown ids are skipped. */
    List<UserSummary> findAllById(Collection<UUID> ids);

    /** Case-insensitive. */
    Optional<UserSummary> findByEmail(String email);

    /** Sets the account status, e.g. ACTIVE after an admin approves a merchant. 404 NOT_FOUND for an unknown id. */
    UserSummary changeStatus(UUID id, UserStatus status);

    /**
     * Creates a password account. {@code normalizedPhone} must already be in +947XXXXXXXX form; the password is hashed
     * here. 409 EMAIL_ALREADY_REGISTERED / PHONE_ALREADY_REGISTERED on duplicates. Used for seeding demo accounts;
     * public sign-up goes through POST /auth/register.
     */
    UserSummary registerUser(String fullName, String email, String normalizedPhone, String rawPassword, Role role,
                             UserStatus status);

    /** Active couriers across all branches, sorted by name. Blocked and removed couriers are excluded. */
    List<CourierSummary> listActiveCouriers();

    /** Active couriers assigned to {@code branch}, sorted by name. Blocked and removed couriers are excluded. */
    List<CourierSummary> listActiveCouriersByBranch(District branch);

    /**
     * Verifies that {@code courierId} refers to an {@link UserStatus#ACTIVE} {@link Role#COURIER} eligible for new
     * parcel assignments.
     *
     * @throws com.swiftdrop.shared.error.NotFoundException     NOT_FOUND when the user does not exist or is not a courier
     * @throws com.swiftdrop.shared.error.BusinessRuleException COURIER_NOT_ASSIGNABLE when the courier is blocked or removed
     */
    CourierSummary requireAssignableCourier(UUID courierId);

    /** Account counts per role, plus merchants waiting for approval, for the admin overview. Removed accounts are left out. */
    UserRoleCounts getUserRoleCounts();

    /**
     * The newest accounts first, at most {@code limit} (capped at 50). Removed accounts are left out.
     *
     * @throws IllegalArgumentException when {@code limit} is less than 1
     */
    List<UserSummary> findRecentUsers(int limit);
}
