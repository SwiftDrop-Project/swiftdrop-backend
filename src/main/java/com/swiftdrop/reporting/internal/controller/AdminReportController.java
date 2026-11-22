package com.swiftdrop.reporting.internal.controller;

import com.swiftdrop.reporting.internal.application.AdminOverviewService;
import com.swiftdrop.reporting.internal.application.AdminReportService;
import com.swiftdrop.reporting.internal.dto.AdminOverviewResponse;
import com.swiftdrop.reporting.internal.dto.ReportSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** Reports for the admin portal. ADMIN only, read only. */
@RestController
@RequestMapping("/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - reports", description = "Figures and reports for the admin portal (ADMIN only)")
public class AdminReportController {

    private final AdminOverviewService overviewService;
    private final AdminReportService reportService;

    public AdminReportController(AdminOverviewService overviewService, AdminReportService reportService) {
        this.overviewService = overviewService;
        this.reportService = reportService;
    }

    @GetMapping("/overview")
    @Operation(summary = "Admin home page figures: accounts per role, parcels today and in the last 7 days, newest accounts")
    public AdminOverviewResponse overview() {
        return overviewService.overview();
    }

    @GetMapping("/summary")
    @Operation(summary = "Reports & Analytics: totals for a range of Sri Lanka days, the period before it, parcels "
            + "per day and parcels by status now")
    public ReportSummaryResponse summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.summary(from, to);
    }

    @GetMapping("/summary/export")
    @Operation(summary = "Download the range's parcels per day as CSV (date, booked, delivered, failed, and a total row)")
    public ResponseEntity<String> summaryExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = reportService.summaryCsv(from, to);
        String fileName = "swiftdrop-report-" + from + "-to-" + to + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv);
    }
}
