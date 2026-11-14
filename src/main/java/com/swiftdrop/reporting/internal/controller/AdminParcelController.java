package com.swiftdrop.reporting.internal.controller;

import com.swiftdrop.delivery.ParcelSort;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.reporting.internal.application.AdminParcelService;
import com.swiftdrop.reporting.internal.application.AdminParcelService.Filters;
import com.swiftdrop.reporting.internal.dto.AdminParcelDetailResponse;
import com.swiftdrop.reporting.internal.dto.AdminParcelDetailResponse.TrackingEntry;
import com.swiftdrop.reporting.internal.dto.AdminParcelResponse;
import com.swiftdrop.reporting.internal.dto.AdminParcelStatsResponse;
import com.swiftdrop.reporting.internal.dto.ParcelPaymentFilter;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Every parcel on the platform, for the admin's All Parcels page. ADMIN only; reads only. Admins do not assign or
 * reassign couriers (dispatchers do, in the dispatch module), so there is deliberately no write endpoint here.
 */
@RestController
@RequestMapping("/admin/parcels")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - parcels", description = "Find, view and export every parcel (ADMIN only)")
public class AdminParcelController {

    private final AdminParcelService parcelService;

    public AdminParcelController(AdminParcelService parcelService) {
        this.parcelService = parcelService;
    }

    @GetMapping("/stats")
    @Operation(summary = "Summary figures: total, waiting for pickup, on the way, delivered, failed, and every status")
    public AdminParcelStatsResponse stats() {
        return parcelService.stats();
    }

    @GetMapping
    @Operation(summary = "List parcels (paginated): search by tracking number, parcel id, name, phone or order ref; "
            + "filter by status, pickup and delivery district, booking date and payment; sort by date or fee")
    public PageResponse<AdminParcelResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<ParcelStatus> status,
            @RequestParam(required = false) District pickupDistrict,
            @RequestParam(required = false) District deliveryDistrict,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) ParcelPaymentFilter payment,
            @RequestParam(defaultValue = "NEWEST") ParcelSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Filters filters = new Filters(q, statuses(status), pickupDistrict, deliveryDistrict, createdFrom, createdTo,
                payment, sort);
        return PageResponse.from(parcelService.list(filters, page, size));
    }

    @GetMapping("/export")
    @Operation(summary = "Download the filtered parcels as CSV (same filters as the list, at most 5,000 rows)")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<ParcelStatus> status,
            @RequestParam(required = false) District pickupDistrict,
            @RequestParam(required = false) District deliveryDistrict,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) ParcelPaymentFilter payment,
            @RequestParam(defaultValue = "NEWEST") ParcelSort sort) {
        Filters filters = new Filters(q, statuses(status), pickupDistrict, deliveryDistrict, createdFrom, createdTo,
                payment, sort);
        String csv = parcelService.exportCsv(filters);
        String fileName = "swiftdrop-parcels-" + LocalDate.now(ZoneId.of("Asia/Colombo")) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One parcel in full: contacts, sender account, fee, proof and status history")
    public AdminParcelDetailResponse get(@PathVariable UUID id) {
        return parcelService.get(id);
    }

    @GetMapping("/{id}/tracking")
    @Operation(summary = "The parcel's status history, newest first")
    public List<TrackingEntry> tracking(@PathVariable UUID id) {
        return parcelService.tracking(id);
    }

    private static Set<ParcelStatus> statuses(List<ParcelStatus> requested) {
        return requested == null || requested.isEmpty() ? Set.of() : EnumSet.copyOf(requested);
    }
}
