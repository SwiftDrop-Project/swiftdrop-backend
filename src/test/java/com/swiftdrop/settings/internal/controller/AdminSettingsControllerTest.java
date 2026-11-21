package com.swiftdrop.settings.internal.controller;

import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.security.AuthCookies;
import com.swiftdrop.identity.internal.security.AuthRateLimiter;
import com.swiftdrop.identity.internal.security.GoogleSignInHandler;
import com.swiftdrop.identity.internal.security.JsonSecurityErrorHandlers;
import com.swiftdrop.identity.internal.security.SecurityConfig;
import com.swiftdrop.identity.internal.security.SecurityProperties;
import com.swiftdrop.identity.internal.security.SessionStateCache;
import com.swiftdrop.identity.internal.security.SessionStateCache.SessionState;
import com.swiftdrop.infrastructure.internal.web.GlobalExceptionHandler;
import com.swiftdrop.settings.internal.application.CompanySettingsService;
import com.swiftdrop.settings.internal.application.DeliverySettingsService;
import com.swiftdrop.settings.internal.application.NotificationSettingsService;
import com.swiftdrop.settings.internal.application.SystemInformationService;
import com.swiftdrop.settings.internal.dto.CompanyDetailsRequest;
import com.swiftdrop.settings.internal.dto.DeliverySettingsRequest;
import com.swiftdrop.settings.internal.dto.NotificationSettingsRequest;
import com.swiftdrop.settings.internal.dto.SystemInformationResponse;
import com.swiftdrop.settings.internal.persistence.DeliverySettingsRepository;
import com.swiftdrop.shared.web.PublicEndpoints;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@code /api/v1/admin/settings/**} with the application's real {@link SecurityConfig},
 * {@link GlobalExceptionHandler} and {@code /api/v1} prefix ({@code WebConfig}, picked up by the slice).
 *
 * <p>Only the web layer starts: no database, RabbitMQ or SMTP. The services and the security beans that would
 * read the database ({@link SessionStateCache}) are mocks. A logged-in user is a JWT built by Spring Security's
 * test support, so the real session, profile and password-change filters run against it.
 */
@WebMvcTest(AdminSettingsController.class)
@Import({SecurityConfig.class, JsonSecurityErrorHandlers.class, GlobalExceptionHandler.class,
        AdminSettingsControllerTest.TestSecurityBeans.class})
class AdminSettingsControllerTest {

    private static final String BASE = "/api/v1/admin/settings";
    private static final int TOKEN_VERSION = 0;

    @Autowired
    private MockMvc mvc;

    @MockitoBean private CompanySettingsService companyService;
    @MockitoBean private DeliverySettingsService deliveryService;
    @MockitoBean private NotificationSettingsService notificationService;
    @MockitoBean private SystemInformationService systemInformationService;

    // Dependencies of the real SecurityConfig that would otherwise need a database or Google.
    @MockitoBean private SessionStateCache sessions;
    @MockitoBean private AuthRateLimiter rateLimiter;
    @MockitoBean private AuthCookies authCookies;
    @MockitoBean private GoogleSignInHandler googleSignInHandler;
    @MockitoBean private SecurityProperties securityProperties;
    @MockitoBean private JwtDecoder jwtDecoder;

    /** The merchants module's API-key filter and a public URL list are not part of this slice: stand-ins. */
    @TestConfiguration
    static class TestSecurityBeans {

        @Bean("apiKeyAuthenticationFilter")
        Filter apiKeyAuthenticationFilter() {
            return (request, response, chain) -> chain.doFilter(request, response);
        }

        @Bean
        PublicEndpoints testPublicEndpoints() {
            return () -> List.of(PublicEndpoints.paths().matcher("/api/v1/test-only-public"));
        }
    }

    @BeforeEach
    void activeSessions() {
        // Every test user has a current, active session (what SessionValidityFilter checks).
        when(sessions.get(any())).thenReturn(Optional.of(new SessionState(UserStatus.ACTIVE, TOKEN_VERSION, false)));
    }

    private static RequestPostProcessor as(String role) {
        return jwt()
                .jwt(token -> token.subject(UUID.randomUUID().toString())
                        .claim("role", role)
                        .claim("ver", TOKEN_VERSION))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static final String VALID_COMPANY = """
            {"companyName":"SwiftDrop","supportEmail":"support@swiftdrop.lk","supportPhone":"0771234567",
             "headOfficeAddress":"Colombo 03","registrationNumber":"PV-1234"}""";

    private static final String VALID_DELIVERY = """
            {"pickupStart":"08:00","pickupEnd":"18:00","sameDayCutoff":"12:00",
             "operatingDays":["Monday","Friday"],"maxWeightKg":25,"maxLengthCm":100,"maxWidthCm":60,
             "maxHeightCm":60,"failedAttempts":3}""";

    private static final String VALID_NOTIFICATIONS = """
            {"parcelStatusEmails":true,"merchantDecisionEmails":false,"senderName":"SwiftDrop"}""";

    // ---------- Authorization: 401 without login, 403 for every non-admin role ----------

    @Test
    void everyEndpointNeedsLogin() throws Exception {
        for (String path : List.of("/company", "/delivery", "/notifications", "/system-info")) {
            mvc.perform(get(BASE + path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        mvc.perform(put(BASE + "/notifications").contentType(MediaType.APPLICATION_JSON).content(VALID_NOTIFICATIONS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminRolesAreForbidden() throws Exception {
        for (String role : List.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER")) {
            mvc.perform(get(BASE + "/notifications").with(as(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(put(BASE + "/company").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_COMPANY))
                    .andExpect(status().isForbidden());
        }
        verify(notificationService, never()).getNotificationSettings();
        verify(companyService, never()).updateCompanyDetails(any());
    }

    @Test
    void sessionThatWasEndedIsRejected() throws Exception {
        // e.g. the admin's account was disabled or their password changed (token version bumped).
        when(sessions.get(any())).thenReturn(Optional.of(new SessionState(UserStatus.ACTIVE, TOKEN_VERSION + 1, false)));
        // The real filter also clears the login cookie on the response.
        when(authCookies.clear()).thenReturn(ResponseCookie.from("SD_TOKEN", "").maxAge(0).build());

        mvc.perform(get(BASE + "/notifications").with(as("ADMIN")))
                .andExpect(status().isUnauthorized());
    }

    // ---------- Notification settings ----------

    @Test
    void adminReadsNotificationSettings() throws Exception {
        when(notificationService.getNotificationSettings())
                .thenReturn(Optional.of(new NotificationSettingsRequest(true, false, "SwiftDrop")));

        mvc.perform(get(BASE + "/notifications").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelStatusEmails").value(true))
                .andExpect(jsonPath("$.merchantDecisionEmails").value(false))
                .andExpect(jsonPath("$.senderName").value("SwiftDrop"));
    }

    @Test
    void notificationSettingsNotSavedYetIs404() throws Exception {
        when(notificationService.getNotificationSettings()).thenReturn(Optional.empty());

        mvc.perform(get(BASE + "/notifications").with(as("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminUpdatesNotificationSettings() throws Exception {
        NotificationSettingsRequest expected = new NotificationSettingsRequest(true, false, "SwiftDrop");
        when(notificationService.updateNotificationSettings(expected)).thenReturn(expected);

        mvc.perform(put(BASE + "/notifications").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_NOTIFICATIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderName").value("SwiftDrop"));

        verify(notificationService).updateNotificationSettings(expected);
    }

    @Test
    void notificationSettingsWithMissingToggleOrBlankNameAre400() throws Exception {
        mvc.perform(put(BASE + "/notifications").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantDecisionEmails":false,"senderName":"  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.parcelStatusEmails").exists())
                .andExpect(jsonPath("$.fieldErrors.senderName").exists());

        verify(notificationService, never()).updateNotificationSettings(any());
    }

    @Test
    void senderNameOver100CharactersIs400() throws Exception {
        String body = """
                {"parcelStatusEmails":true,"merchantDecisionEmails":true,"senderName":"%s"}"""
                .formatted("x".repeat(101));

        mvc.perform(put(BASE + "/notifications").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.senderName").exists());
    }

    // ---------- Company details ----------

    @Test
    void adminUpdatesCompanyDetails() throws Exception {
        CompanyDetailsRequest request = new CompanyDetailsRequest(
                "SwiftDrop", "support@swiftdrop.lk", "0771234567", "Colombo 03", "PV-1234");
        when(companyService.updateCompanyDetails(request)).thenReturn(request);

        mvc.perform(put(BASE + "/company").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_COMPANY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("SwiftDrop"));
    }

    @Test
    void companyDetailsWithBadEmailAndPhoneAre400() throws Exception {
        mvc.perform(put(BASE + "/company").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyName":"SwiftDrop","supportEmail":"not-an-email","supportPhone":"12345",
                                 "headOfficeAddress":"Colombo 03"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.supportEmail").value("Enter a valid email address"))
                .andExpect(jsonPath("$.fieldErrors.supportPhone").value("Enter a valid Sri Lankan mobile number"));

        verify(companyService, never()).updateCompanyDetails(any());
    }

    // ---------- Delivery operations ----------

    @Test
    void adminReadsDeliverySettings() throws Exception {
        when(deliveryService.getDeliverySettings()).thenReturn(Optional.of(new DeliverySettingsRequest(
                LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(12, 0), List.of("Monday"),
                null, null, null, null, 2)));

        mvc.perform(get(BASE + "/delivery").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatingDays[0]").value("Monday"))
                .andExpect(jsonPath("$.failedAttempts").value(2));
    }

    @Test
    void adminUpdatesDeliverySettings() throws Exception {
        when(deliveryService.updateDeliverySettings(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put(BASE + "/delivery").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_DELIVERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedAttempts").value(3));
    }

    @Test
    void deliverySettingsWithMissingTimesOrTooManyAttemptsAre400() throws Exception {
        mvc.perform(put(BASE + "/delivery").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pickupEnd":"18:00","sameDayCutoff":"12:00","operatingDays":["Monday"],
                                 "failedAttempts":9}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.pickupStart").exists())
                .andExpect(jsonPath("$.fieldErrors.failedAttempts").exists());

        verify(deliveryService, never()).updateDeliverySettings(any());
    }

    /** The mocked service runs the real DeliverySettingsService rules (repository mocked, nothing saved). */
    private void useRealDeliveryRules() {
        DeliverySettingsService real = new DeliverySettingsService(mock(DeliverySettingsRepository.class));
        when(deliveryService.updateDeliverySettings(any()))
                .thenAnswer(inv -> real.updateDeliverySettings(inv.getArgument(0)));
    }

    @Test
    void deliveryBusinessRuleErrorIs400WithFieldError() throws Exception {
        // Was 500 INTERNAL_ERROR while the service threw ResponseStatusException (not mapped by the handler).
        useRealDeliveryRules();

        mvc.perform(put(BASE + "/delivery").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_DELIVERY.replace("\"pickupEnd\":\"18:00\"", "\"pickupEnd\":\"07:00\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.pickupEnd").value("Pickup end time must be after start time."));
    }

    @Test
    void deliveryWithNullOperatingDayIs400NotACrash() throws Exception {
        useRealDeliveryRules();

        mvc.perform(put(BASE + "/delivery").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_DELIVERY.replace("[\"Monday\",\"Friday\"]", "[\"Monday\",null]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.operatingDays").value("Invalid or duplicate operating days."));
    }

    // ---------- System information ----------

    @Test
    void adminReadsSystemInformation() throws Exception {
        when(systemInformationService.getSystemInformation()).thenReturn(new SystemInformationResponse(
                "1.0.0", "default", "Connected", "Disconnected", "Enabled"));

        mvc.perform(get(BASE + "/system-info").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseStatus").value("Connected"))
                .andExpect(jsonPath("$.rabbitMqStatus").value("Disconnected"))
                .andExpect(jsonPath("$.outboxRelayStatus").value("Enabled"));
    }
}
