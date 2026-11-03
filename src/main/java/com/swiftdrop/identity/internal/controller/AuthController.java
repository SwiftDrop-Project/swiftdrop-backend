package com.swiftdrop.identity.internal.controller;

import com.swiftdrop.identity.internal.application.AuthService;
import com.swiftdrop.identity.internal.application.AuthService.Account;
import com.swiftdrop.identity.internal.application.AuthService.AuthResult;
import com.swiftdrop.identity.internal.dto.CompleteProfileRequest;
import com.swiftdrop.identity.internal.dto.LoginRequest;
import com.swiftdrop.identity.internal.dto.RegisterRequest;
import com.swiftdrop.identity.internal.dto.UserResponse;
import com.swiftdrop.identity.internal.security.AuthCookies;
import com.swiftdrop.shared.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Register, log in, log out, current user, finish a Google sign-up")
// The /auth/email-verification/** and /auth/password/** endpoints are in AccountRecoveryController.
public class AuthController {

    private static final String CUSTOMER_EXAMPLE = """
            {"accountType": "CUSTOMER", "fullName": "Ayesha Fernando", "phone": "077 123 4567",
             "email": "ayesha@example.com", "password": "secret123", "acceptTerms": true}""";
    private static final String MERCHANT_EXAMPLE = """
            {"accountType": "MERCHANT", "businessName": "Lanka Crafts", "businessRegNo": "PV 00012345",
             "parcelsPerMonth": "FROM_50_TO_200", "contactPerson": "Nimal Perera", "phone": "0712345678",
             "email": "orders@lankacrafts.lk", "pickupDistrict": "COLOMBO", "city": "Nugegoda",
             "password": "secret123", "acceptTerms": true}""";
    private static final String COMPLETE_CUSTOMER_EXAMPLE = """
            {"accountType": "CUSTOMER", "phone": "077 123 4567", "acceptTerms": true}""";
    private static final String COMPLETE_MERCHANT_EXAMPLE = """
            {"accountType": "MERCHANT", "businessName": "Lanka Crafts", "businessRegNo": "PV 00012345",
             "parcelsPerMonth": "FROM_50_TO_200", "contactPerson": "Nimal Perera", "phone": "0712345678",
             "pickupDistrict": "COLOMBO", "city": "Nugegoda", "acceptTerms": true}""";

    private final AuthService authService;
    private final AuthCookies authCookies;

    public AuthController(AuthService authService, AuthCookies authCookies) {
        this.authService = authService;
        this.authCookies = authCookies;
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = """
            Self sign-up (public). Status PENDING_EMAIL_VERIFICATION, no cookie; a 6-digit code is emailed. \
            Then POST /auth/email-verification/verify.""")
    @SecurityRequirements
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
            @ExampleObject(name = "Customer", value = CUSTOMER_EXAMPLE),
            @ExampleObject(name = "Merchant", value = MERCHANT_EXAMPLE)}))
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = switch (request) {
            case RegisterRequest.Customer c ->
                    authService.registerCustomer(c.fullName(), c.email(), c.phone(), c.password());
            case RegisterRequest.Merchant m ->
                    authService.registerMerchant(m.contactPerson(), m.email(), m.phone(), m.password(),
                            m.businessName(), m.businessRegNo(), m.parcelsPerMonth(), m.pickupDistrict(), m.city());
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.token() != null) {
            response.header(HttpHeaders.SET_COOKIE, authCookies.create(result.token(), false).toString());
        }
        return response.body(toResponse(result.account()));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = "Log in with email or mobile number (public). Sets the SD_TOKEN cookie.")
    @SecurityRequirements
    public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request.identifier(), request.password(), request.rememberMeOrDefault());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookies.create(result.token(), request.rememberMeOrDefault()).toString())
                .body(toResponse(result.account()));
    }

    @PostMapping("/logout")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Log out (public). Clears the SD_TOKEN cookie.")
    @SecurityRequirements
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookies.clear().toString())
                .build();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The logged-in user")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return toResponse(authService.currentAccount(CurrentUser.id(jwt)));
    }

    @PostMapping(path = "/complete-profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = """
            Finish a Google sign-up (status PROFILE_INCOMPLETE). CUSTOMER becomes ACTIVE and gets a fresh SD_TOKEN; \
            MERCHANT waits for approval and the cookie is cleared.""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
            @ExampleObject(name = "Customer", value = COMPLETE_CUSTOMER_EXAMPLE),
            @ExampleObject(name = "Merchant", value = COMPLETE_MERCHANT_EXAMPLE)}))
    public ResponseEntity<UserResponse> completeProfile(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody CompleteProfileRequest request) {
        UUID userId = CurrentUser.id(jwt);
        AuthResult result = switch (request) {
            case CompleteProfileRequest.Customer c -> authService.completeCustomerProfile(userId, c.phone());
            case CompleteProfileRequest.Merchant m ->
                    authService.completeMerchantProfile(userId, m.contactPerson(), m.phone(), m.businessName(),
                            m.businessRegNo(), m.parcelsPerMonth(), m.pickupDistrict(), m.city());
        };
        // Same lifetime as the Google sign-in that created the session (remember me).
        ResponseCookie cookie = result.token() != null
                ? authCookies.create(result.token(), true)
                : authCookies.clear();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(toResponse(result.account()));
    }

    private static UserResponse toResponse(Account account) {
        return UserResponse.of(account.user(), account.merchant());
    }
}
