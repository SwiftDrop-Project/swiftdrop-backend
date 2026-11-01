package com.swiftdrop.merchants.api;

import com.swiftdrop.merchants.application.MerchantService;
import com.swiftdrop.merchants.application.MerchantViews.MerchantAccountView;
import com.swiftdrop.merchants.application.MerchantViews.MerchantApplicationView;
import com.swiftdrop.merchants.application.MerchantViews.MerchantProfileView;
import com.swiftdrop.merchants.domain.ApprovalStatus;
import com.swiftdrop.merchants.domain.ParcelsPerMonth;
import com.swiftdrop.shared.meta.District;
import com.swiftdrop.shared.security.CurrentUser;
import com.swiftdrop.users.api.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Merchant applications. ADMIN only (enforced in SecurityConfig for /admin/**). */
@RestController
@RequestMapping("/admin/merchants")
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
    public UserResponse approve(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return toUserResponse(merchantService.approve(userId, CurrentUser.id(jwt)));
    }

    @PostMapping("/{userId}/reject")
    @Operation(summary = "Reject a merchant: user status becomes REJECTED")
    public UserResponse reject(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return toUserResponse(merchantService.reject(userId, CurrentUser.id(jwt)));
    }

    static UserResponse toUserResponse(MerchantAccountView account) {
        MerchantProfileView p = account.profile();
        return UserResponse.of(account.user(), new UserResponse.MerchantInfo(p.businessName(), p.pickupDistrict(),
                p.city(), p.approvalStatus().name()));
    }

    public record MerchantApplicationResponse(UUID userId, String businessName, String contactPerson, String email,
                                              String phone, District pickupDistrict, String city,
                                              ParcelsPerMonth parcelsPerMonth, Instant createdAt) {

        static MerchantApplicationResponse from(MerchantApplicationView v) {
            return new MerchantApplicationResponse(v.userId(), v.businessName(), v.contactPerson(), v.email(),
                    v.phone(), v.pickupDistrict(), v.city(), v.parcelsPerMonth(), v.createdAt());
        }
    }
}
