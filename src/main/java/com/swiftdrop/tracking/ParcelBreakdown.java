package com.swiftdrop.tracking;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.shared.reference.District;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parcels of a period by district and failure reason, from tracking's own index, for the admin reports. Days are
 * Sri Lanka days, {@code from} to {@code to} both included. Each map is ordered from the largest count down and leaves
 * out zeros.
 *
 * @param pickupDistricts  parcels booked in the period, by the district they are collected from
 * @param dropOffDistricts parcels booked in the period, by the district they are delivered to
 * @param failureReasons   parcels whose delivery failed in the period (and are still FAILED), by reason
 */
public record ParcelBreakdown(
        LocalDate from,
        LocalDate to,
        Map<District, Long> pickupDistricts,
        Map<District, Long> dropOffDistricts,
        Map<FailureReason, Long> failureReasons) {

    public ParcelBreakdown {
        pickupDistricts = Collections.unmodifiableMap(new LinkedHashMap<>(pickupDistricts));
        dropOffDistricts = Collections.unmodifiableMap(new LinkedHashMap<>(dropOffDistricts));
        failureReasons = Collections.unmodifiableMap(new LinkedHashMap<>(failureReasons));
    }
}
