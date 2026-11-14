package com.swiftdrop.reporting.internal.application;

import com.swiftdrop.delivery.DeliverySnapshot;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** One parcel per CSV line for GET /admin/parcels/export (RFC 4180, CRLF line ends). */
final class ParcelCsv {

    static final String HEADER = line(List.of("Tracking number", "Parcel ID", "Status", "Sender type", "Sender",
            "Sender phone", "Pickup address", "Pickup city", "Pickup district", "Receiver", "Receiver phone",
            "Delivery address", "Delivery city", "Delivery district", "Weight (kg)", "Delivery fee", "Currency",
            "Cash on delivery", "Created (UTC)", "Last updated (UTC)"));

    private ParcelCsv() {
    }

    static String row(DeliverySnapshot parcel) {
        return line(Stream.of(
                parcel.trackingNumber(),
                parcel.id(),
                parcel.status(),
                parcel.merchantId() != null ? "MERCHANT" : "CUSTOMER",
                parcel.pickup().name(),
                parcel.pickup().phone(),
                parcel.pickup().addressLine(),
                parcel.pickup().city(),
                parcel.pickup().district(),
                parcel.recipient().name(),
                parcel.recipient().phone(),
                parcel.recipient().addressLine(),
                parcel.recipient().city(),
                parcel.recipient().district(),
                plain(parcel.weightKg()),
                plain(parcel.feeAmount()),
                parcel.currency(),
                plain(parcel.codAmount()),
                parcel.createdAt(),
                parcel.updatedAt()).map(value -> value == null ? "" : value.toString()).toList());
    }

    private static String line(List<String> values) {
        return values.stream().map(ParcelCsv::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    /**
     * Quotes a value when needed. Text that a spreadsheet would run as a formula (starting with =, +, -, @, tab or
     * carriage return) gets a leading apostrophe, so an exported name like "=HYPERLINK(...)" stays plain text.
     */
    static String cell(String value) {
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            safe = "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }
}
