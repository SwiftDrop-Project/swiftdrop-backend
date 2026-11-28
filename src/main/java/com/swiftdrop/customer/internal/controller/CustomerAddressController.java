package com.swiftdrop.customer.internal.controller;

import com.swiftdrop.customer.internal.application.CustomerAddressService;
import com.swiftdrop.customer.internal.dto.CustomerAddressRequest;
import com.swiftdrop.customer.internal.dto.CustomerAddressResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The logged-in customer's saved pickup and drop-off addresses. The customer always comes from the login; someone
 * else's address and an unknown id are both 404 NOT_FOUND.
 */
@RestController
@RequestMapping("/customer/addresses")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Customer - Addresses", description = "Saved pickup and drop-off addresses")
public class CustomerAddressController {

    private final CustomerAddressService addresses;

    public CustomerAddressController(CustomerAddressService addresses) {
        this.addresses = addresses;
    }

    @GetMapping
    @Operation(summary = "My saved addresses, the default pickup address first")
    public List<CustomerAddressResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return addresses.list(CurrentUser.id(jwt));
    }

    @PostMapping
    @Operation(summary = "Save an address (up to 20); useAsDefault makes it the default pickup address")
    public ResponseEntity<CustomerAddressResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                          @Valid @RequestBody CustomerAddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(addresses.create(CurrentUser.id(jwt), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change one of my addresses")
    public CustomerAddressResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @Valid @RequestBody CustomerAddressRequest request) {
        return addresses.update(CurrentUser.id(jwt), id, request);
    }

    @PutMapping("/{id}/default")
    @Operation(summary = "Make one of my addresses the default pickup address")
    public CustomerAddressResponse makeDefault(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return addresses.makeDefault(CurrentUser.id(jwt), id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete one of my addresses (deleting the default leaves no default)")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        addresses.delete(CurrentUser.id(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
