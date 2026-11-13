package com.swiftdrop.courier.internal.application;

import com.swiftdrop.courier.internal.application.CourierViews.MyItem;
import com.swiftdrop.courier.internal.application.CourierViews.ParcelDetail;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.dispatch.AssignmentApi;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One parcel as the courier sees it: its status and history, and the courier's own items for it. A courier sees a
 * parcel only if it is (or was) in one of their assignments; anything else is {@code 404 NOT_FOUND}.
 */
@Service
public class CourierParcelService {

    private final AssignmentApi assignmentApi;
    private final DeliveryApi delivery;

    public CourierParcelService(AssignmentApi assignmentApi, DeliveryApi delivery) {
        this.assignmentApi = assignmentApi;
        this.delivery = delivery;
    }

    public ParcelDetail detail(UUID courierId, UUID parcelId) {
        List<AssignmentItemView> items = assignmentApi.findItemsForCourierAndParcel(courierId, parcelId);
        if (items.isEmpty()) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        DeliverySnapshot parcel = delivery.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));

        // Usually one or two assignments per parcel, so one read each is fine.
        Map<UUID, Optional<AssignmentView>> byId = new HashMap<>();
        List<MyItem> myItems = new ArrayList<>();
        for (AssignmentItemView item : items) {
            byId.computeIfAbsent(item.assignmentId(), id -> assignmentApi.findByIdForCourier(id, courierId)
                            .filter(assignment -> courierId.equals(assignment.courierId())))
                    .ifPresent(assignment -> myItems.add(MyItem.of(assignment, item)));
        }
        if (myItems.isEmpty()) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        return new ParcelDetail(parcel, delivery.history(parcelId), myItems);
    }
}
