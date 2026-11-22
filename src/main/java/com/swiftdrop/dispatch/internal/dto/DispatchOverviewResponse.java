package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.shared.reference.District;

/**
 * Today's figures for one branch.
 *
 * @param waitingPickup   parcels in the branch's district waiting for a pickup task
 * @param waitingAtBranch parcels at the branch waiting for a transfer or delivery task
 * @param assignedTasks   tasks the courier hasn't started
 * @param inProgressTasks tasks under way
 * @param completedToday  tasks completed today (Sri Lanka time)
 * @param issuesThisWeek  parcels not collected, not loaded or not delivered in the last 7 days
 * @param activeCouriers  active couriers based at the branch
 */
public record DispatchOverviewResponse(
        District branch,
        long waitingPickup,
        long waitingAtBranch,
        long assignedTasks,
        long inProgressTasks,
        long completedToday,
        long issuesThisWeek,
        long activeCouriers) {
}
