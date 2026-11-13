package com.swiftdrop.tracking.internal.controller;

import com.swiftdrop.shared.reference.District;
import com.swiftdrop.tracking.internal.application.LiveUpdates;
import com.swiftdrop.tracking.internal.application.TrackingRateLimiter;
import com.swiftdrop.tracking.internal.application.TrackingService;
import com.swiftdrop.tracking.internal.dto.LiveEvent;
import com.swiftdrop.tracking.internal.dto.TrackingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
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

    TrackingController(TrackingService tracking, TrackingRateLimiter rateLimiter, LiveUpdates liveUpdates) {
        this.tracking = tracking;
        this.rateLimiter = rateLimiter;
        this.liveUpdates = liveUpdates;
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
}
