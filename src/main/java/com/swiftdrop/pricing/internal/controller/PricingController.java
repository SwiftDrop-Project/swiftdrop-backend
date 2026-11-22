package com.swiftdrop.pricing.internal.controller;

import com.swiftdrop.pricing.PricingApi;
import com.swiftdrop.pricing.internal.dto.FeeEstimateRequest;
import com.swiftdrop.pricing.internal.dto.FeeEstimateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The fee estimate shown before booking. Nothing is saved; booking prices the parcel with the same quote. */
@RestController
@RequestMapping("/pricing")
@PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
@Tag(name = "Pricing")
public class PricingController {

    private final PricingApi pricing;

    public PricingController(PricingApi pricing) {
        this.pricing = pricing;
    }

    @PostMapping("/estimate")
    @Operation(summary = "Estimate the delivery fee for a parcel and route, with the breakdown")
    public FeeEstimateResponse estimate(@Valid @RequestBody FeeEstimateRequest request) {
        return FeeEstimateResponse.from(pricing.quote(request.toQuoteRequest()));
    }
}
