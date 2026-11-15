package com.swiftdrop.identity;

/**
 * How many accounts there are per role, for the admin overview. Removed (soft-deleted) accounts are never counted;
 * every other status is (active, disabled, waiting for email verification or approval, ...).
 *
 * @param totalUsers       all accounts that are not removed
 * @param pendingMerchants merchants waiting for an admin to approve them (status PENDING_APPROVAL)
 */
public record UserRoleCounts(
        long totalUsers,
        long totalCustomers,
        long totalMerchants,
        long totalCouriers,
        long totalDispatchers,
        long totalAdmins,
        long pendingMerchants
) {
}
