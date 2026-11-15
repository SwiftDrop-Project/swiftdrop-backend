package com.swiftdrop.tracking.internal.controller;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.web.PageResponse;
import com.swiftdrop.tracking.internal.application.ParcelIndexQueries;
import com.swiftdrop.tracking.internal.dto.IndexedParcelResponse;
import com.swiftdrop.tracking.internal.dto.ParcelStatusCountsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin's all-parcels tracking list. Live changes come over WebSocket (/topic/live), see API.md "Tracking". */
@RestController
@RequestMapping("/admin/tracking/parcels")
@Tag(name = "Tracking")
@PreAuthorize("hasRole('ADMIN')")
class AdminTrackingController {

    private final ParcelIndexQueries queries;

    AdminTrackingController(ParcelIndexQueries queries) {
        this.queries = queries;
    }

    @Operation(summary = """
            Every parcel's latest status, most recently updated first (ADMIN). Filter by part of a tracking number, \
            status, district (sender's, recipient's or the branch it is at) and the day it was last updated.""")
    @GetMapping
    PageResponse<IndexedParcelResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ParcelStatus status,
            @RequestParam(required = false) District district,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "The page number can't be negative.");
        }
        if (size < 1 || size > 100) {
            throw new FieldValidationException("size", "Choose a page size between 1 and 100.");
        }
        return queries.search(filter(q, status, district, from, to), page, size);
    }

    @Operation(summary = "How many parcels match the filters, per status (ADMIN). The status filter itself is ignored.")
    @GetMapping("/counts")
    ParcelStatusCountsResponse counts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) District district,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queries.counts(filter(q, null, district, from, to));
    }

    private static ParcelIndexQueries.Filter filter(String q, ParcelStatus status, District district, LocalDate from,
                                                    LocalDate to) {
        if (q != null && q.length() > 40) {
            throw new FieldValidationException("q", "Enter at most 40 characters.");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new FieldValidationException("to", "The end date can't be before the start date.");
        }
        return new ParcelIndexQueries.Filter(q, status, district, from, to);
    }
}
