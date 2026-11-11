package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.VehicleType;

import java.util.UUID;

/**
 * An active courier and how much work they have.
 *
 * @param openTasks      tasks not finished yet (ASSIGNED or IN_PROGRESS)
 * @param openParcels    parcels in those tasks the courier hasn't finished with
 * @param completedToday tasks completed today (Sri Lanka time)
 */
public record CourierWorkloadResponse(
        UUID courierId,
        String fullName,
        String phone,
        District branch,
        VehicleType vehicleType,
        long openTasks,
        long openParcels,
        long completedToday) {
}
