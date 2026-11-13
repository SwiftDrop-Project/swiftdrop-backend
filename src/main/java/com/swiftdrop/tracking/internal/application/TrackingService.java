package com.swiftdrop.tracking.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.tracking.internal.dto.TrackingPlace;
import com.swiftdrop.tracking.internal.dto.TrackingResponse;
import com.swiftdrop.tracking.internal.dto.TrackingStep;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Public parcel lookup by tracking number: only what a stranger holding the number may see. */
@Service
public class TrackingService {

    /** {@code SD} + yyMM + 6 digits, e.g. {@code SD2611004817}, after spaces are removed and letters upper-cased. */
    private static final Pattern TRACKING_NUMBER = Pattern.compile("SD\\d{10}");

    static final String NOT_FOUND_MESSAGE =
            "We couldn't find a parcel with this tracking number. Check the number and try again.";

    private final DeliveryApi deliveries;
    private final ParcelIndexer indexer;

    public TrackingService(DeliveryApi deliveries, ParcelIndexer indexer) {
        this.deliveries = deliveries;
        this.indexer = indexer;
    }

    /** "sd 2611 0048 17" → "SD2611004817", or null when it can't be a tracking number. */
    public static String normalize(String trackingNumber) {
        if (trackingNumber == null) {
            return null;
        }
        String value = trackingNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return TRACKING_NUMBER.matcher(value).matches() ? value : null;
    }

    /**
     * The parcel's public view. 404 for a number in the wrong format too, so the answer gives nothing away. A parcel
     * that is looked up is also added to the all-parcels index (it may predate the index).
     */
    public TrackingResponse track(String trackingNumber) {
        String number = normalize(trackingNumber);
        if (number == null) {
            throw new NotFoundException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        DeliverySnapshot parcel = deliveries.findByTrackingNumber(number)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE));
        indexer.index(parcel);
        List<TrackingStep> history = deliveries.history(parcel.id()).stream()
                .sorted(Comparator.comparing(ParcelStatusEntry::occurredAt))
                .map(entry -> new TrackingStep(entry.status(), entry.branch(), entry.failureReason(),
                        entry.occurredAt()))
                .toList();
        return new TrackingResponse(
                parcel.trackingNumber(),
                parcel.status(),
                parcel.currentBranch(),
                place(parcel.pickup()),
                place(parcel.recipient()),
                parcel.createdAt(),
                parcel.updatedAt(),
                parcel.version(),
                history);
    }

    private static TrackingPlace place(DeliveryContact contact) {
        return contact == null ? null : new TrackingPlace(contact.city(), contact.district());
    }
}
