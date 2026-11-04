package com.swiftdrop.merchants.internal.controller;

import com.swiftdrop.merchants.internal.application.ApiKeyService;
import com.swiftdrop.merchants.internal.dto.ApiKeyResponse;
import com.swiftdrop.merchants.internal.dto.CreateApiKeyRequest;
import com.swiftdrop.merchants.internal.dto.CreateApiKeyResponse;
import com.swiftdrop.shared.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/merchant/api-keys")
@PreAuthorize("hasRole('MERCHANT')")
@Tag(name = "Merchant - API Keys")
public class MerchantApiKeyController {

    private final ApiKeyService apiKeyService;

    public MerchantApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @Operation(summary = "Create API key (raw key shown once — store securely)")
    public ResponseEntity<CreateApiKeyResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateApiKeyRequest request) {
        UUID merchantId = CurrentUser.id(jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(apiKeyService.createKey(merchantId, request.name()));
    }

    @GetMapping
    @Operation(summary = "List API keys")
    public List<ApiKeyResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return apiKeyService.listKeys(CurrentUser.id(jwt));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke API key")
    public ApiKeyResponse revoke(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return apiKeyService.revokeKey(CurrentUser.id(jwt), id);
    }

    @PostMapping("/{id}/rotate")
    @Operation(summary = "Rotate API key (revokes old, creates new — raw key shown once)")
    public CreateApiKeyResponse rotate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return apiKeyService.rotateKey(CurrentUser.id(jwt), id);
    }
}
