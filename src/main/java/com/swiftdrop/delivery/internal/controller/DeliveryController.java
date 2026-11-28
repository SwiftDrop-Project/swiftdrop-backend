package com.swiftdrop.delivery.internal.controller;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.internal.dto.CreateDeliveryRequest;
import com.swiftdrop.delivery.internal.dto.DeliveryDetailResponse;
import com.swiftdrop.delivery.internal.dto.DeliveryResponse;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/**
 * A customer's own deliveries: booking them and "My deliveries". The customer always comes from the login, never
 * from the request. Merchants send and list their orders through the merchants module (/merchant/orders).
 */
@RestController
@RequestMapping("/deliveries")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Deliveries")
public class DeliveryController {

    /** The largest page of "My deliveries" (the same cap as DeliveryApi.findByCustomer). */
    static final int MAX_PAGE_SIZE = 100;

    /** The longest tracking number search ("SD 2611 0031 52" is 15 characters with its spaces). */
    static final int MAX_SEARCH_LENGTH = 30;

    private final DeliveryApi deliveries;

    public DeliveryController(DeliveryApi deliveries) {
        this.deliveries = deliveries;
    }

    @PostMapping
    @Operation(summary = "Book a delivery (the server sets the fee and the tracking number)")
    public ResponseEntity<DeliveryResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody CreateDeliveryRequest request) {
        DeliverySnapshot parcel = deliveries.create(request.toCommand(CurrentUser.id(jwt)));
        return ResponseEntity.status(HttpStatus.CREATED).body(DeliveryResponse.from(parcel));
    }

    /**
     * A bad page, size or search is a 400 here; the API is not asked to cap it quietly. {@code status} may be repeated
     * ({@code ?status=PICKED_UP&status=AT_BRANCH}) for the "Active" tab; {@code q} is part of a tracking number.
     */
    @GetMapping
    @Operation(summary = "My deliveries, newest first, optionally only some statuses or a tracking number search")
    public PageResponse<DeliveryResponse> list(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) Set<ParcelStatus> status,
                                               @RequestParam(required = false) String q) {
        if (page < 0) {
            throw new FieldValidationException("page", "Use a page number of 0 or more.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new FieldValidationException("size", "Use a page size from 1 to " + MAX_PAGE_SIZE + ".");
        }
        if (q != null && q.length() > MAX_SEARCH_LENGTH) {
            throw new FieldValidationException("q", "Use at most " + MAX_SEARCH_LENGTH + " characters.");
        }
        return PageResponse.from(deliveries.findByCustomer(CurrentUser.id(jwt), status == null ? Set.of() : status, q,
                PageRequest.of(page, size)), DeliveryResponse::from);
    }

    /**
     * Someone else's delivery, a merchant order and an unknown id all answer 404 NOT_FOUND, so nobody can find out
     * which ids exist.
     */
    @GetMapping("/{id}")
    @Operation(summary = "One of my deliveries with its status history, newest first")
    public DeliveryDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID customerId = CurrentUser.id(jwt);
        DeliverySnapshot parcel = deliveries.findById(id)
                .filter(found -> customerId.equals(found.customerId()))
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        return DeliveryDetailResponse.from(parcel, deliveries.history(id));
    }
}
