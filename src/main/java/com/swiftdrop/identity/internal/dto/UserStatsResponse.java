package com.swiftdrop.identity.internal.dto;

/**
 * GET /admin/users/stats: the summary cards of the User Management page. Removed accounts are never counted.
 *
 * @param total         every account that is not removed
 * @param active        status ACTIVE
 * @param blocked       status DISABLED (blocked by an admin)
 * @param pending       email not confirmed, merchant waiting for approval, or Google sign-up not finished
 * @param newLast30Days created in the last 30 days
 */
public record UserStatsResponse(long total, long active, long blocked, long pending, long newLast30Days) {
}
