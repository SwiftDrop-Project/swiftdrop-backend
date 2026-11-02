package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.identity.internal.application.UserService;
import com.swiftdrop.identity.internal.security.GoogleSignInHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Continue with Google" with fake credentials. Google itself is never called: the browser-facing redirects are
 * tested through MockMvc, and the result of a successful Google login (an OIDC user) is handed to the success
 * handler directly, exactly as Spring Security does after exchanging the code.
 */
@TestPropertySource(properties = {
        "swiftdrop.security.google.client-id=test-client-id.apps.googleusercontent.com",
        "swiftdrop.security.google.client-secret=test-client-secret"
})
class GoogleSignInTest extends IntegrationTest {

    private static final String FRONTEND = "http://localhost:3000";
    private static final String CALLBACK = FRONTEND + "/auth/callback";
    private static final String REQUEST_COOKIE = "SD_OAUTH2_REQUEST";
    private static final String COMPLETE_PROFILE = "/api/v1/auth/complete-profile";

    @Autowired
    GoogleSignInHandler handler;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserService userService;

    // ---------- redirect to Google and back (no Google calls) ----------

    @Test
    void startRedirectsToGoogleAndKeepsTheRequestInACookieNotASession() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        Map<String, String> query = queryParams(location);
        assertThat(query.get("client_id")).isEqualTo("test-client-id.apps.googleusercontent.com");
        assertThat(query.get("response_type")).isEqualTo("code");
        assertThat(query.get("scope").split(" ")).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(query.get("redirect_uri")).isEqualTo("http://localhost/login/oauth2/code/google");
        assertThat(query.get("state")).isNotBlank();
        assertThat(query.get("nonce")).isNotBlank();

        assertThat(setCookie(result.getResponse(), REQUEST_COOKIE))
                .contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=300");
        assertThat(result.getRequest().getSession(false)).as("no HTTP session").isNull();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith("JSESSIONID"));
    }

    @Test
    void cancelledConsentRedirectsWithGoogleFailed() throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/google")).andReturn();
        String state = queryParams(start.getResponse().getRedirectedUrl()).get("state");
        Cookie requestCookie = start.getResponse().getCookie(REQUEST_COOKIE);

        MvcResult result = mvc.perform(get("/login/oauth2/code/google")
                        .param("error", "access_denied").param("state", state).cookie(requestCookie))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=google_failed");
        assertThat(setCookieHeader(result)).isNull();
        assertThat(setCookie(result.getResponse(), REQUEST_COOKIE)).contains("Max-Age=0");
    }

    @Test
    void callbackWithoutOrWithTamperedRequestCookieRedirectsWithGoogleFailed() throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/google")).andReturn();
        String state = queryParams(start.getResponse().getRedirectedUrl()).get("state");
        String value = start.getResponse().getCookie(REQUEST_COOKIE).getValue();
        String tampered = value.substring(0, value.indexOf('.')) + ".AAAA" + value.substring(value.indexOf('.') + 5);

        for (Cookie[] cookies : List.of(new Cookie[0], new Cookie[]{new Cookie(REQUEST_COOKIE, tampered)})) {
            var request = get("/login/oauth2/code/google").param("code", "fake-code").param("state", state);
            if (cookies.length > 0) {
                request.cookie(cookies);
            }
            MvcResult result = mvc.perform(request).andExpect(status().isFound()).andReturn();
            assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=google_failed");
            assertThat(setCookieHeader(result)).isNull();
        }
    }

    @Test
    void noGeneratedLoginPage() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- after Google: find, link or create the account ----------

    @Test
    void newGoogleUserIsCreatedAsProfileIncompleteAndLoggedIn() throws Exception {
        String sub = newSub();
        String email = uniqueEmail();

        MockHttpServletResponse response = googleSignIn(sub, email.toUpperCase(), "Kamal Silva", true);

        assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK);
        assertThat(setCookie(response, COOKIE))
                .contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=604800").doesNotContain("Secure");

        mvc.perform(get("/api/v1/auth/me").cookie(tokenCookie(response)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Kamal Silva"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.phone").isEmpty())
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.status").value("PROFILE_INCOMPLETE"))
                .andExpect(jsonPath("$.merchant").isEmpty());

        Map<String, Object> row = jdbc.queryForMap(
                "select google_sub, password_hash, phone from identity.users where email = ?", email);
        assertThat(row.get("google_sub")).isEqualTo(sub);
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("phone")).isNull();
    }

    @Test
    void existingPasswordUserWithSameEmailIsLinkedAndKeepsTheirRole() throws Exception {
        String email = uniqueEmail();
        String userId = registerMerchant(email, uniquePhone());
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());
        String sub = newSub();

        MockHttpServletResponse response = googleSignIn(sub, email.toUpperCase(), "Someone Else", true);

        assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK);
        mvc.perform(get("/api/v1/auth/me").cookie(tokenCookie(response)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.fullName").value("Nimal Perera"))
                .andExpect(jsonPath("$.role").value("MERCHANT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("APPROVED"));
        assertThat(jdbc.queryForObject("select google_sub from identity.users where email = ?", String.class, email))
                .isEqualTo(sub);

        // The password still works, and the Google account is found by its id even if its email changes.
        login(email, PASSWORD, false).andExpect(status().isOk());
        MockHttpServletResponse again = googleSignIn(sub, uniqueEmail(), "Someone Else", true);
        mvc.perform(get("/api/v1/auth/me").cookie(tokenCookie(again)))
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void pendingMerchantGetsNoCookieAndAnErrorRedirect() throws Exception {
        String email = uniqueEmail();
        registerMerchant(email, uniquePhone());

        MockHttpServletResponse response = googleSignIn(newSub(), email, "Nimal Perera", true);

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=account_pending_approval");
        assertThat(setCookie(response, COOKIE)).isNull();
    }

    @Test
    void rejectedAndDisabledAccountsGetErrorRedirects() throws Exception {
        String merchantEmail = uniqueEmail();
        String merchantId = registerMerchant(merchantEmail, uniquePhone());
        mvc.perform(post("/api/v1/admin/merchants/{id}/reject", merchantId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());
        MockHttpServletResponse rejected = googleSignIn(newSub(), merchantEmail, "Nimal Perera", true);
        assertThat(rejected.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=account_rejected");
        assertThat(setCookie(rejected, COOKIE)).isNull();

        String customerEmail = uniqueEmail();
        registerCustomer(customerEmail, uniquePhone());
        UUID customerId = userService.findLoginAccountByEmail(customerEmail).orElseThrow().id();
        userService.changeStatus(customerId, UserStatus.DISABLED);
        MockHttpServletResponse disabled = googleSignIn(newSub(), customerEmail, "Ayesha Fernando", true);
        assertThat(disabled.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=account_disabled");
        assertThat(setCookie(disabled, COOKIE)).isNull();
    }

    @Test
    void unverifiedEmailIsRejectedWithGoogleFailed() throws Exception {
        for (Boolean verified : new Boolean[]{false, null}) {
            String email = uniqueEmail();

            MockHttpServletResponse response = googleSignIn(newSub(), email, "Kamal Silva", verified);

            assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=google_failed");
            assertThat(setCookie(response, COOKIE)).isNull();
            assertThat(userService.existsByEmail(email)).isFalse();
        }
    }

    @Test
    void emailLinkedToAnotherGoogleAccountIsRejected() throws Exception {
        String email = uniqueEmail();
        googleSignIn(newSub(), email, "Kamal Silva", true);

        MockHttpServletResponse response = googleSignIn(newSub(), email, "Kamal Silva", true);

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=google_failed");
        assertThat(setCookie(response, COOKIE)).isNull();
    }

    @Test
    void googleOnlyAccountCannotUsePasswordLogin() throws Exception {
        String email = uniqueEmail();
        googleSignIn(newSub(), email, "Kamal Silva", true);

        login(email, PASSWORD, false)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("The mobile number, email or password is incorrect."));
    }

    // ---------- PROFILE_INCOMPLETE ----------

    @Test
    void profileIncompleteUserCanOnlySeeThemselvesCompleteProfileAndLogOut() throws Exception {
        Cookie cookie = tokenCookie(googleSignIn(newSub(), uniqueEmail(), "Kamal Silva", true));

        mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isOk());
        for (String path : List.of("/api/v1/admin/merchants", "/api/v1/nothing-here")) {
            mvc.perform(get(path).cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"))
                    .andExpect(jsonPath("$.message").value("Finish setting up your account to continue."));
        }
        mvc.perform(post("/api/v1/auth/logout").cookie(cookie)).andExpect(status().isNoContent());
    }

    @Test
    void completeProfileAsCustomerActivatesAndRefreshesTheCookie() throws Exception {
        String sub = newSub();
        String email = uniqueEmail();
        Cookie incomplete = tokenCookie(googleSignIn(sub, email, "Kamal Silva", true));
        String phone = uniquePhone();

        MvcResult result = postJson(COMPLETE_PROFILE, customerProfile(phone.substring(0, 3) + " " + phone.substring(3)),
                incomplete)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Kamal Silva"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.phone").value(stored(phone)))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.merchant").isEmpty())
                .andReturn();

        assertThat(setCookieHeader(result)).contains("HttpOnly", "SameSite=Lax", "Max-Age=604800");
        Cookie active = tokenCookie(result);
        // No longer restricted: an unknown path is a normal 404 and admin is a normal 403.
        mvc.perform(get("/api/v1/nothing-here").cookie(active))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/merchants").cookie(active))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Sign in with Google again: straight in, already ACTIVE; the phone now works as a login identifier too.
        MockHttpServletResponse again = googleSignIn(sub, email, "Kamal Silva", true);
        mvc.perform(get("/api/v1/auth/me").cookie(tokenCookie(again)))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void completeProfileAsMerchantWaitsForApprovalAndClearsTheCookie() throws Exception {
        String sub = newSub();
        String email = uniqueEmail();
        Cookie incomplete = tokenCookie(googleSignIn(sub, email, "Kamal Silva", true));
        String phone = uniquePhone();

        MvcResult result = postJson(COMPLETE_PROFILE, merchantProfile(phone), incomplete)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Nimal Perera"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.phone").value(stored(phone)))
                .andExpect(jsonPath("$.role").value("MERCHANT"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.merchant.businessName").value("Lanka Crafts"))
                .andExpect(jsonPath("$.merchant.pickupDistrict").value("COLOMBO"))
                .andExpect(jsonPath("$.merchant.city").value("Nugegoda"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("PENDING"))
                .andReturn();
        String userId = json.readTree(result.getResponse().getContentAsString()).get("id").asString();

        assertThat(setCookieHeader(result)).contains("Max-Age=0");

        MockHttpServletResponse pending = googleSignIn(sub, email, "Kamal Silva", true);
        assertThat(pending.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?error=account_pending_approval");

        Cookie admin = loginAsAdmin();
        mvc.perform(get("/api/v1/admin/merchants").cookie(admin))
                .andExpect(jsonPath("$[?(@.userId == '%s')].contactPerson", userId).value("Nimal Perera"));
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(admin))
                .andExpect(status().isOk());

        MockHttpServletResponse approved = googleSignIn(sub, email, "Kamal Silva", true);
        assertThat(approved.getRedirectedUrl()).isEqualTo(CALLBACK);
        mvc.perform(get("/api/v1/auth/me").cookie(tokenCookie(approved)))
                .andExpect(jsonPath("$.role").value("MERCHANT"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("APPROVED"));
    }

    @Test
    void invalidCompleteProfileBodyReturns400WithRegisterMessages() throws Exception {
        Cookie cookie = tokenCookie(googleSignIn(newSub(), uniqueEmail(), "Kamal Silva", true));

        postJson(COMPLETE_PROFILE, Map.of("accountType", "CUSTOMER", "phone", "0112345678", "acceptTerms", false),
                cookie)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.phone").value("Enter a Sri Lankan mobile number, like 077 123 4567."))
                .andExpect(jsonPath("$.fieldErrors.acceptTerms").value("Accept the terms to continue."));

        Map<String, Object> merchant = merchantProfile(uniquePhone());
        merchant.put("businessName", "");
        merchant.put("businessRegNo", "X".repeat(31));
        merchant.remove("parcelsPerMonth");
        merchant.put("contactPerson", " ");
        merchant.remove("pickupDistrict");
        merchant.put("city", "C");
        postJson(COMPLETE_PROFILE, merchant, cookie)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.businessName").value("Enter your business name."))
                .andExpect(jsonPath("$.fieldErrors.businessRegNo").value("Use at most 30 characters."))
                .andExpect(jsonPath("$.fieldErrors.parcelsPerMonth").value("Select a range."))
                .andExpect(jsonPath("$.fieldErrors.contactPerson").value("Enter your full name."))
                .andExpect(jsonPath("$.fieldErrors.pickupDistrict").value("Select a district."))
                .andExpect(jsonPath("$.fieldErrors.city").value("Enter your city or town."));

        Map<String, Object> courier = customerProfile(uniquePhone());
        courier.put("accountType", "COURIER");
        postJson(COMPLETE_PROFILE, courier, cookie)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.accountType").exists());

        mvc.perform(get("/api/v1/auth/me").cookie(cookie))
                .andExpect(jsonPath("$.status").value("PROFILE_INCOMPLETE"));
    }

    @Test
    void duplicatePhoneReturns409() throws Exception {
        String phone = uniquePhone();
        registerCustomer(uniqueEmail(), phone);
        Cookie cookie = tokenCookie(googleSignIn(newSub(), uniqueEmail(), "Kamal Silva", true));

        postJson(COMPLETE_PROFILE, customerProfile(stored(phone)), cookie)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));
        postJson(COMPLETE_PROFILE, merchantProfile(phone), cookie)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    void completeProfileTwiceOrForANormalAccountReturns409() throws Exception {
        Cookie cookie = tokenCookie(googleSignIn(newSub(), uniqueEmail(), "Kamal Silva", true));
        postJson(COMPLETE_PROFILE, merchantProfile(uniquePhone()), cookie).andExpect(status().isOk());

        // The cookie was cleared, but the old token is still signed and unexpired.
        postJson(COMPLETE_PROFILE, customerProfile(uniquePhone()), cookie)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_ALREADY_COMPLETE"));

        Cookie passwordUser = registerCustomer(uniqueEmail(), uniquePhone());
        postJson(COMPLETE_PROFILE, customerProfile(uniquePhone()), passwordUser)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_ALREADY_COMPLETE"));
    }

    @Test
    void completeProfileNeedsLogin() throws Exception {
        postJson(COMPLETE_PROFILE, customerProfile(uniquePhone()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- helpers ----------

    /** Plays the part of Spring Security after Google returned a valid ID token for this user. */
    private MockHttpServletResponse googleSignIn(String sub, String email, String name, Boolean emailVerified)
            throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(StandardClaimNames.SUB, sub);
        claims.put(StandardClaimNames.EMAIL, email);
        claims.put(StandardClaimNames.NAME, name);
        if (emailVerified != null) {
            claims.put(StandardClaimNames.EMAIL_VERIFIED, emailVerified);
        }
        OidcIdToken idToken = new OidcIdToken("mock-id-token", Instant.now(), Instant.now().plusSeconds(300), claims);
        OidcUser user = new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
        var authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest("GET", "/login/oauth2/code/google"), response,
                authentication);
        return response;
    }

    private static Cookie tokenCookie(MockHttpServletResponse response) {
        Cookie cookie = response.getCookie(COOKIE);
        assertThat(cookie).as("SD_TOKEN cookie").isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        return new Cookie(COOKIE, cookie.getValue());
    }

    private static String setCookie(MockHttpServletResponse response, String name) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(name + "=")).findFirst().orElse(null);
    }

    private static Map<String, String> queryParams(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams().forEach((key, values) ->
                params.put(key, URLDecoder.decode(values.getFirst(), StandardCharsets.UTF_8)));
        return params;
    }

    private static String newSub() {
        return "google-" + UUID.randomUUID();
    }

    private static Map<String, Object> customerProfile(String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountType", "CUSTOMER");
        body.put("phone", phone);
        body.put("acceptTerms", true);
        return body;
    }

    private static Map<String, Object> merchantProfile(String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountType", "MERCHANT");
        body.put("businessName", "Lanka Crafts");
        body.put("businessRegNo", "PV 00012345");
        body.put("parcelsPerMonth", "FROM_50_TO_200");
        body.put("contactPerson", "Nimal Perera");
        body.put("phone", phone);
        body.put("pickupDistrict", "COLOMBO");
        body.put("city", "Nugegoda");
        body.put("acceptTerms", true);
        return body;
    }
}
