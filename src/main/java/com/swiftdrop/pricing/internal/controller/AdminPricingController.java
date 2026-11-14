package com.swiftdrop.pricing.internal.controller;

import com.swiftdrop.pricing.PricingApi;
import com.swiftdrop.pricing.internal.application.AdminPricingService;
import com.swiftdrop.pricing.internal.dto.AdminRateCardResponse;
import com.swiftdrop.pricing.internal.dto.DistrictCentreResponse;
import com.swiftdrop.pricing.internal.dto.FeeEstimateRequest;
import com.swiftdrop.pricing.internal.dto.FeeEstimateResponse;
import com.swiftdrop.pricing.internal.dto.RouteTableResponse;
import com.swiftdrop.shared.reference.District;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The admin's Zones &amp; Pricing page (API.md "Zones &amp; pricing (admin, WP4)"). ADMIN only and read only: the
 * estimate saves nothing, and rate cards change only through Flyway migrations.
 */
@RestController
@RequestMapping("/admin/pricing")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - zones & pricing", description = "Rate card, district centres, routes and fee calculator (ADMIN only)")
public class AdminPricingController {

    private final AdminPricingService adminPricing;
    private final PricingApi pricing;

    public AdminPricingController(AdminPricingService adminPricing, PricingApi pricing) {
        this.adminPricing = adminPricing;
        this.pricing = pricing;
    }

    @GetMapping("/rate-card")
    @Operation(summary = "The active rate card with its vehicles and their limits")
    public AdminRateCardResponse rateCard() {
        return adminPricing.activeRateCard();
    }

    @GetMapping("/rate-cards")
    @Operation(summary = "Every rate card version, newest first, with its vehicles (previous ones are kept for history)")
    public List<AdminRateCardResponse> rateCards() {
        return adminPricing.allRateCards();
    }

    @GetMapping("/districts")
    @Operation(summary = "Every district (branch) with the centre distances are measured from")
    public List<DistrictCentreResponse> districts() {
        return adminPricing.districts();
    }

    @GetMapping("/routes")
    @Operation(summary = "Distance and linehaul from one branch to every district")
    public RouteTableResponse routes(@RequestParam District from) {
        return adminPricing.routesFrom(from);
    }

    @PostMapping("/estimate")
    @Operation(summary = "Price calculator: the same quote a booking gets, with the breakdown. Nothing is saved")
    public FeeEstimateResponse estimate(@Valid @RequestBody FeeEstimateRequest request) {
        return FeeEstimateResponse.from(pricing.quote(request.toQuoteRequest()));
    }
}
