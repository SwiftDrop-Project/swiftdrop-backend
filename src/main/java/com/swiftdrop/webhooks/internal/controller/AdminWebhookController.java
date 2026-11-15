package com.swiftdrop.webhooks.internal.controller;

import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import com.swiftdrop.webhooks.internal.application.DeliveryFilters;
import com.swiftdrop.webhooks.internal.application.WebhookDeliveryService;
import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;
import com.swiftdrop.webhooks.internal.dto.AdminWebhookDeliveryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Admin view of every merchant's webhook deliveries (API.md "Webhooks"). /admin/** also needs ADMIN in the filter chain. */
@RestController
@RequestMapping("/admin/webhooks")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Webhooks")
public class AdminWebhookController {

    private final WebhookDeliveryService deliveries;

    public AdminWebhookController(WebhookDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @GetMapping("/deliveries")
    @Operation(summary = "Every merchant's webhook deliveries, newest first. Optional filters: status, merchantId, "
            + "eventType, search (tracking number, order number or an id), from/to (days, Sri Lankan time, included)")
    public PageResponse<AdminWebhookDeliveryResponse> list(@RequestParam(required = false) DeliveryStatus status,
                                                           @RequestParam(required = false) UUID merchantId,
                                                           @RequestParam(required = false) String eventType,
                                                           @RequestParam(required = false) String search,
                                                           @RequestParam(required = false) String from,
                                                           @RequestParam(required = false) String to,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return deliveries.adminList(merchantId, new DeliveryFilters(status, eventType, search, from, to), page, size);
    }

    @PostMapping("/deliveries/{id}/retry")
    @Operation(summary = "Send a FAILED delivery again, with a new set of attempts")
    public AdminWebhookDeliveryResponse retry(Authentication authentication, @PathVariable UUID id) {
        return deliveries.adminRetry(adminId(authentication), id);
    }

    private static UUID adminId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return CurrentUser.id(jwt.getToken());
        }
        throw new ForbiddenException(ErrorCode.FORBIDDEN);
    }
}
