package com.swiftdrop.dispatch.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.dispatch.internal.dto.ParcelAssignmentResponse;
import com.swiftdrop.identity.CourierSummary;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Coordinates courier eligibility checks in {@link IdentityApi} with parcel validation in {@link DeliveryApi}.
 * Blocked ({@code DISABLED}) and soft-removed ({@code REMOVED}) couriers are rejected immediately even when their UUID
 * is submitted directly, while existing parcel assignments and history remain untouched when a courier is later blocked
 * or removed. Multi-branch leg persistence belongs to WP8 and answers {@code 503 SERVICE_UNAVAILABLE} until built.
 */
@Service
public class DispatchService {

    private final IdentityApi identity;
    private final DeliveryApi deliveries;

    public DispatchService(IdentityApi identity, DeliveryApi deliveries) {
        this.identity = identity;
        this.deliveries = deliveries;
    }

    @Transactional(readOnly = true)
    public List<CourierSummary> listActiveCouriers(District branch) {
        return branch == null ? identity.listActiveCouriers() : identity.listActiveCouriersByBranch(branch);
    }

    @Transactional(readOnly = true)
    public ParcelAssignmentResponse assignCourier(UUID parcelId, UUID courierId, UUID actorId) {
        identity.requireAssignableCourier(courierId);
        DeliverySnapshot parcel = deliveries.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (parcel.status().isFinished()) {
            throw new BusinessRuleException(ErrorCode.PARCEL_NOT_ASSIGNABLE);
        }
        throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}

