package com.swiftdrop.merchants.internal.controller;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.merchants.internal.application.MerchantOrderService;
import com.swiftdrop.merchants.internal.dto.CreateMerchantOrderRequest;
import com.swiftdrop.merchants.internal.dto.MerchantOrderResponse;
import com.swiftdrop.merchants.internal.security.MerchantPrincipal;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A merchant's own orders: creating them and listing them. The merchant id always comes from the login (cookie or
 * API key), never from the request. Idempotency: resending the same {@code merchantOrderRef} returns the existing
 * parcel (handled in {@link com.swiftdrop.delivery.DeliveryApi#create}).
 */
@RestController
@RequestMapping("/merchant/orders")
@PreAuthorize("hasRole('MERCHANT')")
@Tag(name = "Merchant - Orders")
public class MerchantOrderController {

    static final int MAX_PAGE_SIZE = 100;

    private final MerchantOrderService orders;

    public MerchantOrderController(MerchantOrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @Operation(summary = "Create a merchant order (the server sets the fee and the tracking number)")
    public ResponseEntity<MerchantOrderResponse> create(Authentication authentication,
                                                        @Valid @RequestBody CreateMerchantOrderRequest request) {
        UUID merchantId = MerchantPrincipal.id(authentication);
        DeliverySnapshot parcel = orders.createOrder(merchantId, request.toCommand(merchantId));
        return ResponseEntity.status(HttpStatus.CREATED).body(MerchantOrderResponse.from(parcel));
    }

    @GetMapping
    @Operation(summary = "My orders, newest first, optionally only one status")
    public PageResponse<MerchantOrderResponse> list(Authentication authentication,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(required = false) ParcelStatus status) {
        if (page < 0) {
            throw new FieldValidationException("page", "Use a page number of 0 or more.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new FieldValidationException("size", "Use a page size from 1 to " + MAX_PAGE_SIZE + ".");
        }
        UUID merchantId = MerchantPrincipal.id(authentication);
        return PageResponse.from(orders.listOrders(merchantId, status, PageRequest.of(page, size)),
                MerchantOrderResponse::from);
    }
}
