package com.swiftdrop.tracking.internal.controller;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import com.swiftdrop.tracking.internal.application.LiveUpdates;
import com.swiftdrop.tracking.internal.application.MyParcels;
import com.swiftdrop.tracking.internal.application.TrackingRateLimiter;
import com.swiftdrop.tracking.internal.application.TrackingService;
import com.swiftdrop.tracking.internal.dto.IndexedParcelResponse;
import com.swiftdrop.tracking.internal.dto.LiveEvent;
import com.swiftdrop.tracking.internal.dto.TrackingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parcel tracking. Live updates come over WebSocket (STOMP), see API.md "Tracking": these endpoints give the starting
 * point a client applies the updates to.
 */
@RestController
@Tag(name = "Tracking")
class TrackingController {

    private final TrackingService tracking;
    private final TrackingRateLimiter rateLimiter;
    private final LiveUpdates liveUpdates;
    private final MyParcels myParcels;

    TrackingController(TrackingService tracking, TrackingRateLimiter rateLimiter, LiveUpdates liveUpdates,
                       MyParcels myParcels) {
        this.tracking = tracking;
        this.rateLimiter = rateLimiter;
        this.liveUpdates = liveUpdates;
        this.myParcels = myParcels;
    }

    @Operation(summary = """
            Track a parcel by its tracking number (public). Spaces and case are ignored. Shows the status, the towns \
            and the steps so far; never names, phones, addresses or notes. Limited per IP.""")
    @PreAuthorize("permitAll()")
    @GetMapping("/track/{trackingNumber}")
    TrackingResponse track(@PathVariable String trackingNumber, HttpServletRequest request) {
        rateLimiter.check(request.getRemoteAddr());
        return tracking.track(trackingNumber);
    }

    @Operation(summary = """
            The latest parcel status changes seen by this server, newest first (DISPATCHER, ADMIN), optionally only \
            those that start, end or arrive at a branch. New ones are pushed to /topic/live.""")
    @PreAuthorize("hasAnyRole('DISPATCHER','ADMIN')")
    @GetMapping("/tracking/live/recent")
    List<LiveEvent> recent(@RequestParam(required = false) District branch) {
        return liveUpdates.recent(branch, 100);
    }

    @Operation(summary = """
            The logged-in customer's or merchant's own parcels, newest first, optionally only one status. Updates to             them are pushed privately to /user/queue/parcels on the logged-in WebSocket endpoint.""")
    @PreAuthorize("hasAnyRole('CUSTOMER','MERCHANT')")
    @GetMapping("/tracking/mine")
    PageResponse<IndexedParcelResponse> mine(JwtAuthenticationToken auth,
                                             @RequestParam(required = false) ParcelStatus status,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "The page number can't be negative.");
        }
        if (size < 1 || size > 100) {
            throw new FieldValidationException("size", "Choose a page size between 1 and 100.");
        }
        boolean merchant = auth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_MERCHANT".equals(authority.getAuthority()));
        MyParcels.Owner owner = merchant ? MyParcels.Owner.MERCHANT : MyParcels.Owner.CUSTOMER;
        return myParcels.page(owner, CurrentUser.id(auth.getToken()), status, page, size);
    }
}
