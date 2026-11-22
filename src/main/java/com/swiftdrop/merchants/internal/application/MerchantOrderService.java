package com.swiftdrop.merchants.internal.application;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.merchants.ApprovalStatus;
import com.swiftdrop.merchants.MerchantApi;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.ForbiddenException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Creates merchant orders and lists a merchant's own orders. Delegates the actual work to {@link DeliveryApi};
 * this module only owns the merchant-facing endpoint and the approval gate.
 *
 * <p>The approval gate: cookie-authenticated merchants may hold a valid session while their merchant account is still
 * {@code PENDING} or {@code REJECTED}. API-key callers are already gated by {@code ApiKeyAuthenticationFilter}, but
 * the check here runs for both paths so the rule lives in one place.
 */
@Service
public class MerchantOrderService {

    private final DeliveryApi deliveries;
    private final MerchantApi merchants;

    public MerchantOrderService(DeliveryApi deliveries, MerchantApi merchants) {
        this.deliveries = deliveries;
        this.merchants = merchants;
    }

    public DeliverySnapshot createOrder(UUID merchantUserId, CreateDeliveryCommand command) {
        requireApproved(merchantUserId);
        return deliveries.create(command);
    }

    public Page<DeliverySnapshot> listOrders(UUID merchantUserId, ParcelStatus status, Pageable pageable) {
        requireApproved(merchantUserId);
        return deliveries.findByMerchant(merchantUserId, status, pageable);
    }

    private void requireApproved(UUID merchantUserId) {
        boolean approved = merchants.findByUserId(merchantUserId)
                .map(summary -> summary.approvalStatus() == ApprovalStatus.APPROVED)
                .orElse(false);
        if (!approved) {
            throw new ForbiddenException(ErrorCode.MERCHANT_NOT_APPROVED);
        }
    }
}
