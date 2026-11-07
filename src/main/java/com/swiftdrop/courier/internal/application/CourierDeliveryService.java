package com.swiftdrop.courier.internal.application;

import com.swiftdrop.courier.internal.application.CourierViews.CourierToday;
import com.swiftdrop.courier.internal.application.CourierViews.ParcelDetail;
import com.swiftdrop.courier.internal.dto.StatusChangeRequest;
import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The courier's parcels, read and changed only through {@link DeliveryApi}.
 *
 * <p>Ownership: a courier only sees a parcel whose {@code assignedCourierId} is their own id; anything else is
 * {@code 404 NOT_FOUND}, also when delivery itself refuses the change (the parcel was reassigned in between).
 *
 * <p>{@code DeliveryApi} comes through an {@link ObjectProvider} because nothing implements it until WP3 is merged
 * (or {@code SWIFTDROP_FAKE_DELIVERY=true}). The application still starts; the endpoints answer
 * {@code 503 SERVICE_UNAVAILABLE}. Replace it with constructor injection once delivery is implemented.
 */
@Service
public class CourierDeliveryService {

    /** The role name delivery records as the actor of a courier's change. */
    static final String ACTOR_ROLE = "COURIER";
    static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(CourierDeliveryService.class);

    /** Pickups first, then deliveries (oldest assignment first), then finished parcels (newest first). */
    private static final Comparator<DeliverySnapshot> TODAY_ORDER = Comparator
            .comparingInt(CourierDeliveryService::group)
            .thenComparing((a, b) -> group(a) == 2
                    ? compareNullsLast(b.updatedAt(), a.updatedAt())
                    : compareNullsLast(a.assignedAt(), b.assignedAt()));

    private final ObjectProvider<DeliveryApi> deliveries;
    private final Clock clock;

    @Autowired
    public CourierDeliveryService(ObjectProvider<DeliveryApi> deliveries) {
        this(deliveries, Clock.system(CourierDay.ZONE));
    }

    CourierDeliveryService(ObjectProvider<DeliveryApi> deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnWhenDeliveryIsMissing() {
        if (deliveries.getIfAvailable() == null) {
            log.warn("No DeliveryApi implementation: courier endpoints answer 503 SERVICE_UNAVAILABLE until delivery "
                    + "is implemented (or SWIFTDROP_FAKE_DELIVERY=true for demo data).");
        }
    }

    /** Open parcels assigned to the courier, plus the ones they finished today (Sri Lankan time). */
    public CourierToday today(UUID courierId) {
        List<DeliverySnapshot> parcels = delivery().findForCourier(courierId, CourierDay.startOfToday(clock))
                .stream()
                .filter(parcel -> courierId.equals(parcel.assignedCourierId()))
                .sorted(TODAY_ORDER)
                .toList();
        return new CourierToday(CourierDay.today(clock), parcels);
    }

    /** Delivered and failed parcels, newest first. {@code trackingNumber} is optional; spaces and case are ignored. */
    public Page<DeliverySnapshot> history(UUID courierId, int page, int size, String trackingNumber) {
        if (page < 0) {
            throw new FieldValidationException("page", "Use a page number of 0 or more.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new FieldValidationException("size", "Use a page size from 1 to " + MAX_PAGE_SIZE + ".");
        }
        return delivery().findFinishedForCourier(courierId, normalizeTrackingNumber(trackingNumber),
                PageRequest.of(page, size));
    }

    public ParcelDetail detail(UUID courierId, UUID parcelId) {
        DeliveryApi delivery = delivery();
        DeliverySnapshot parcel = ownParcel(delivery, courierId, parcelId);
        return new ParcelDetail(parcel, delivery.history(parcelId));
    }

    /**
     * Moves the courier's parcel to the requested status. Order of checks: request rules (400), ownership (404),
     * transition from the current status (409); then delivery applies it.
     */
    public ParcelDetail changeStatus(UUID courierId, UUID parcelId, StatusChangeRequest request) {
        DeliveryApi delivery = delivery();
        ValidChange change = validate(request);
        DeliverySnapshot parcel = ownParcel(delivery, courierId, parcelId);
        if (!parcel.status().canMoveTo(request.status())) {
            throw new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        DeliverySnapshot updated;
        try {
            updated = delivery.changeStatus(new ChangeStatusCommand(parcelId, request.status(), courierId, ACTOR_ROLE,
                    change.note(), change.failureReason(), change.recipientName(), null));
        } catch (ForbiddenException ex) {
            // Reassigned to another courier between our check and the change: not this courier's parcel any more.
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        return new ParcelDetail(updated, delivery.history(parcelId));
    }

    private DeliveryApi delivery() {
        DeliveryApi delivery = deliveries.getIfAvailable();
        if (delivery == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return delivery;
    }

    private static DeliverySnapshot ownParcel(DeliveryApi delivery, UUID courierId, UUID parcelId) {
        return delivery.findById(parcelId)
                .filter(parcel -> courierId.equals(parcel.assignedCourierId()))
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    /** The rules that depend on the target status (API.md "Validation rules (courier)"). */
    private static ValidChange validate(StatusChangeRequest request) {
        ParcelStatus target = request.status();
        String note = trimToNull(request.note());
        String recipientName = trimToNull(request.recipientName());
        FailureReason reason = request.failureReason();

        if (recipientName != null && target != ParcelStatus.DELIVERED) {
            throw new FieldValidationException("recipientName", "Only for delivered parcels.");
        }
        if (reason != null && target != ParcelStatus.FAILED) {
            throw new FieldValidationException("failureReason", "Only for failed deliveries.");
        }
        if (target == ParcelStatus.DELIVERED && (recipientName == null || recipientName.length() < 2)) {
            throw new FieldValidationException("recipientName", "Enter the name of the person who received the parcel.");
        }
        if (target == ParcelStatus.FAILED && reason == null) {
            throw new FieldValidationException("failureReason", "Select why the delivery failed.");
        }
        if (reason == FailureReason.OTHER && (note == null || note.length() < 3)) {
            throw new FieldValidationException("note", "Describe what went wrong.");
        }
        return new ValidChange(note, reason, recipientName);
    }

    private record ValidChange(String note, FailureReason failureReason, String recipientName) {
    }

    static String normalizeTrackingNumber(String trackingNumber) {
        if (trackingNumber == null) {
            return null;
        }
        String normalized = trackingNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 0 = pickup (CREATED), 1 = on the way, 2 = finished. */
    private static int group(DeliverySnapshot parcel) {
        if (parcel.status().isFinished()) {
            return 2;
        }
        return parcel.status() == ParcelStatus.CREATED ? 0 : 1;
    }

    private static int compareNullsLast(Instant a, Instant b) {
        return Comparator.nullsLast(Comparator.<Instant>naturalOrder()).compare(a, b);
    }
}
