package com.swiftdrop.merchants.internal.controller;

import com.swiftdrop.merchants.ApprovalStatus;
import com.swiftdrop.merchants.internal.application.MerchantService;
import com.swiftdrop.merchants.internal.dto.MerchantAccountResponse;
import com.swiftdrop.merchants.internal.dto.MerchantApplicationResponse;
import com.swiftdrop.shared.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Merchant applications. ADMIN only. */
@RestController
@RequestMapping("/admin/merchants")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - merchants", description = "Approve or reject merchant sign-ups (ADMIN only)")
public class AdminMerchantController {

    private final MerchantService merchantService;

    public AdminMerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @GetMapping
    @Operation(summary = "List merchant applications by status (default PENDING), oldest first")
    public List<MerchantApplicationResponse> list(@RequestParam(defaultValue = "PENDING") ApprovalStatus status) {
        return merchantService.listApplications(status).stream().map(MerchantApplicationResponse::from).toList();
    }

    @PostMapping("/{userId}/approve")
    @Operation(summary = "Approve a merchant: user status becomes ACTIVE")
    public MerchantAccountResponse approve(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return MerchantAccountResponse.from(merchantService.approve(userId, CurrentUser.id(jwt)));
    }

    @PostMapping("/{userId}/reject")
    @Operation(summary = "Reject a merchant: user status becomes REJECTED")
    public MerchantAccountResponse reject(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return MerchantAccountResponse.from(merchantService.reject(userId, CurrentUser.id(jwt)));
    }
}
