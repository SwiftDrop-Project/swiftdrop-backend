package com.swiftdrop.webhooks.internal.controller;

import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import com.swiftdrop.webhooks.internal.application.DeliveryFilters;
import com.swiftdrop.webhooks.internal.application.WebhookDeliveryService;
import com.swiftdrop.webhooks.internal.application.WebhookSubscriptionService;
import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;
import com.swiftdrop.webhooks.internal.dto.CreateWebhookRequest;
import com.swiftdrop.webhooks.internal.dto.UpdateWebhookRequest;
import com.swiftdrop.webhooks.internal.dto.WebhookDeliveryResponse;
import com.swiftdrop.webhooks.internal.dto.WebhookResponse;
import com.swiftdrop.webhooks.internal.dto.WebhookWithSecretResponse;
import com.swiftdrop.webhooks.internal.security.WebhookActionLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Merchant webhook subscriptions (API.md "Webhooks"). Logged-in merchants only (the SD_TOKEN cookie): a request
 * authenticated with an X-Api-Key header also has role MERCHANT, but gets 403 here.
 */
@RestController
@RequestMapping("/merchant/webhooks")
@PreAuthorize("hasRole('MERCHANT')")
@Tag(name = "Merchant - Webhooks")
public class MerchantWebhookController {

    private final WebhookSubscriptionService service;
    private final WebhookDeliveryService deliveries;
    private final WebhookActionLimiter limiter;

    public MerchantWebhookController(WebhookSubscriptionService service, WebhookDeliveryService deliveries,
                                     WebhookActionLimiter limiter) {
        this.service = service;
        this.deliveries = deliveries;
        this.limiter = limiter;
    }

    @PostMapping
    @Operation(summary = "Create a webhook subscription (the secret is shown once - store it securely)")
    public ResponseEntity<WebhookWithSecretResponse> create(Authentication authentication,
                                                     @Valid @RequestBody CreateWebhookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(merchantId(authentication), request));
    }

    @GetMapping
    @Operation(summary = "List your webhook subscriptions, newest first")
    public List<WebhookResponse> list(Authentication authentication) {
        return service.list(merchantId(authentication));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change a webhook subscription (only the fields sent)")
    public WebhookResponse update(Authentication authentication, @PathVariable UUID id,
                           @Valid @RequestBody UpdateWebhookRequest request) {
        UUID merchantId = merchantId(authentication);
        return inOneStep(() -> service.update(merchantId, id, request));
    }

    /**
     * Idempotent, also when two requests turn it off at the same moment: the one that loses the race answers 204 too,
     * as long as the subscription ended up off. Only if someone turned it back on meanwhile is it 409.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Turn a webhook subscription off (its history is kept)")
    public ResponseEntity<Void> deactivate(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = merchantId(authentication);
        try {
            service.deactivate(merchantId, id);
        } catch (OptimisticLockingFailureException ex) {
            if (service.isActive(merchantId, id)) {
                throw new ConflictException(ErrorCode.WEBHOOK_CHANGED);
            }
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rotate-secret")
    @Operation(summary = "Replace the signing secret (the new secret is shown once; the old one stops working)")
    public WebhookWithSecretResponse rotateSecret(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = merchantId(authentication);
        return inOneStep(() -> service.rotateSecret(merchantId, id));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Send a test ping to this subscription within seconds (also while it is turned off)")
    public ResponseEntity<WebhookDeliveryResponse> test(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = merchantId(authentication);
        limiter.check(merchantId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(deliveries.queuePing(merchantId, id));
    }

    @GetMapping("/deliveries")
    @Operation(summary = "All your webhook deliveries, from every subscription, newest first. Optional filters: "
            + "status, eventType, search (a parcel id, tracking number, order number or another id), from/to (days, "
            + "Sri Lankan time, included). search=<parcel id> gives every message sent about that parcel")
    public PageResponse<WebhookDeliveryResponse> allDeliveries(Authentication authentication,
                                                               @RequestParam(required = false) DeliveryStatus status,
                                                               @RequestParam(required = false) String eventType,
                                                               @RequestParam(required = false) String search,
                                                               @RequestParam(required = false) String from,
                                                               @RequestParam(required = false) String to,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return deliveries.merchantList(merchantId(authentication),
                new DeliveryFilters(status, eventType, search, from, to), page, size);
    }

    @GetMapping("/{id}/deliveries")
    @Operation(summary = "Delivery history of one subscription, newest first. Optional filters: status, eventType "
            + "(e.g. parcel.delivered or webhook.ping), search (tracking number, order number or an id), from/to "
            + "(days like 2026-11-15, Sri Lankan time, included)")
    public PageResponse<WebhookDeliveryResponse> deliveries(Authentication authentication, @PathVariable UUID id,
                                                            @RequestParam(required = false) DeliveryStatus status,
                                                            @RequestParam(required = false) String eventType,
                                                            @RequestParam(required = false) String search,
                                                            @RequestParam(required = false) String from,
                                                            @RequestParam(required = false) String to,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return deliveries.history(merchantId(authentication), id,
                new DeliveryFilters(status, eventType, search, from, to), page, size);
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    @Operation(summary = "Send a FAILED delivery again, with a new set of attempts")
    public WebhookDeliveryResponse retry(Authentication authentication, @PathVariable UUID deliveryId) {
        UUID merchantId = merchantId(authentication);
        limiter.check(merchantId);
        return deliveries.retry(merchantId, deliveryId);
    }

    /**
     * Runs one change to a subscription. Someone else changed the same subscription at the same moment (optimistic
     * lock, possibly only at commit, after the service returned): 409 WEBHOOK_CHANGED instead of a 500, so the
     * merchant refreshes. Nothing was saved; a rotated secret that lost the race is never shown.
     */
    static <T> T inOneStep(Supplier<T> change) {
        try {
            return change.get();
        } catch (OptimisticLockingFailureException ex) {
            throw new ConflictException(ErrorCode.WEBHOOK_CHANGED);
        }
    }

    /** Cookie (JWT) logins only; API-key requests are refused. */
    private static UUID merchantId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return CurrentUser.id(jwt.getToken());
        }
        throw new ForbiddenException(ErrorCode.FORBIDDEN);
    }
}
