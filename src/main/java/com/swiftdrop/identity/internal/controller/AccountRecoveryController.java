package com.swiftdrop.identity.internal.controller;

import com.swiftdrop.identity.internal.application.AuthService;
import com.swiftdrop.identity.internal.application.AuthService.AuthResult;
import com.swiftdrop.identity.internal.dto.ChangePasswordRequest;
import com.swiftdrop.identity.internal.dto.ForgotPasswordRequest;
import com.swiftdrop.identity.internal.dto.MessageResponse;
import com.swiftdrop.identity.internal.dto.ResendVerificationRequest;
import com.swiftdrop.identity.internal.dto.ResetPasswordRequest;
import com.swiftdrop.identity.internal.dto.UserResponse;
import com.swiftdrop.identity.internal.dto.VerifyEmailRequest;
import com.swiftdrop.identity.internal.security.AuthCookies;
import com.swiftdrop.identity.internal.security.TokenService;
import com.swiftdrop.shared.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Email verification after sign-up, and forgot / reset / change password. Codes are 6 digits, sent by email, valid
 * for 10 minutes and dead after 5 wrong tries. The public endpoints never reveal whether an account exists.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "Account recovery", description = "Email verification codes and passwords")
public class AccountRecoveryController {

    static final String RESEND_MESSAGE = "If this email is waiting for verification, we've sent a new code.";
    static final String FORGOT_MESSAGE = "If an account matches, we've sent a code to its email.";

    private final AuthService authService;
    private final AuthCookies authCookies;
    private final TokenService tokenService;

    public AccountRecoveryController(AuthService authService, AuthCookies authCookies, TokenService tokenService) {
        this.authService = authService;
        this.authCookies = authCookies;
        this.tokenService = tokenService;
    }

    @PostMapping(path = "/email-verification/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = """
            Confirm the email with the 6-digit code (public). CUSTOMER becomes ACTIVE and gets the SD_TOKEN cookie \
            (as with rememberMe false); MERCHANT becomes PENDING_APPROVAL, no cookie.""")
    @SecurityRequirements
    public ResponseEntity<UserResponse> verify(@Valid @RequestBody VerifyEmailRequest request) {
        AuthResult result = authService.verifyEmail(request.email(), request.code());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.token() != null) {
            response.header(HttpHeaders.SET_COOKIE, authCookies.create(result.token(), false).toString());
        }
        return response.body(UserResponse.of(result.account().user(), result.account().merchant()));
    }

    @PostMapping(path = "/email-verification/resend", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = """
            Send a new verification code (public). Always 202, whether or not the email is registered. \
            At most one code per 60 seconds and 5 per hour.""")
    @SecurityRequirements
    public ResponseEntity<MessageResponse> resend(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MessageResponse(RESEND_MESSAGE));
    }

    @PostMapping(path = "/password/forgot", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = """
            Email a password reset code to the account with this email or mobile number (public). Always 202 with \
            the same message, whether or not an account matches.""")
    @SecurityRequirements
    public ResponseEntity<MessageResponse> forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.identifier());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MessageResponse(FORGOT_MESSAGE));
    }

    @PostMapping(path = "/password/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = """
            Set a new password with the code from the reset email (public). Logs the account out everywhere; \
            the user then logs in with the new password.""")
    @SecurityRequirements
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.identifier(), request.code(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/password/change", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = """
            Change the password (logged in). Other sessions end; this one gets a fresh SD_TOKEN cookie with the \
            same lifetime.""")
    public ResponseEntity<Void> change(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        boolean rememberMe = tokenService.isRememberMe(jwt);
        String token = authService.changePassword(CurrentUser.id(jwt), request.currentPassword(),
                request.newPassword(), rememberMe);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookies.create(token, rememberMe).toString())
                .build();
    }
}
