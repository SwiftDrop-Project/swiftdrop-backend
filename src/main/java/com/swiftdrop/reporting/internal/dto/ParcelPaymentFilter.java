package com.swiftdrop.reporting.internal.dto;

/** The {@code payment} filter of GET /admin/parcels. SwiftDrop has no payment records yet, only cash on delivery. */
public enum ParcelPaymentFilter {
    /** Already paid: nothing to collect on delivery. */
    PREPAID,
    /** The courier collects cash on delivery. */
    CASH_ON_DELIVERY
}
