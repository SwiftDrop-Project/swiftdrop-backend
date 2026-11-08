package com.swiftdrop;

import com.swiftdrop.identity.internal.security.AuthRateLimiter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for API tests: real PostgreSQL and RabbitMQ in Docker, one shared Spring context.
 * The inline properties win over the local .env, so tests behave the same on every laptop and in CI.
 * Tests never touch the .env database (e.g. the shared Neon database): Testcontainers' @ServiceConnection replaces
 * the connection, and the datasource properties below point to a host that cannot exist, so if Testcontainers were
 * ever unwired the tests would fail instead of reaching a real database (see DatabaseIsolationTest).
 * Every test creates its own users with unique emails and phones, so no cleanup is needed.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=" + IntegrationTest.UNREACHABLE_DB_URL,
        "spring.datasource.username=tests-must-use-testcontainers",
        "spring.datasource.password=tests-must-use-testcontainers",
        "swiftdrop.security.jwt.secret=test-only-jwt-secret-0123456789-abcdefghijklmnopqrstuvwxyz",
        "swiftdrop.security.jwt.expiry-minutes=60",
        "swiftdrop.security.jwt.remember-me-days=7",
        "swiftdrop.security.cookie-secure=false",
        "swiftdrop.security.frontend-url=http://localhost:3000",
        // Google sign-in off by default (GoogleSignInTest turns it on), whatever the local .env says.
        "swiftdrop.security.google.client-id=",
        "swiftdrop.security.google.client-secret=",
        "swiftdrop.seed.admin-email=" + IntegrationTest.ADMIN_EMAIL,
        "swiftdrop.seed.admin-password=" + IntegrationTest.ADMIN_PASSWORD,
        "swiftdrop.seed.demo-users=false",
        "swiftdrop.seed.demo-password=",
        // no Mailpit in tests: emails are captured by CapturingMailSender
        "management.health.mail.enabled=false",
        "swiftdrop.mail.from=no-reply@test.swiftdrop.lk",
        "swiftdrop.security.verification-codes.secret=test-only-code-secret-0123456789-abcdefghijklmnop",
        "swiftdrop.email-checks.mx-enabled=true",
        // TEMPORARY (courier demo data): a local .env with SWIFTDROP_FAKE_DELIVERY / SWIFTDROP_FAKE_DISPATCH=true must
        // not change the tests.
        "swiftdrop.dev.fake-delivery=false",
        "swiftdrop.dev.fake-dispatch=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestDoublesConfiguration.class})
public abstract class IntegrationTest {

    protected static final String VERIFY_PATH = "/api/v1/auth/email-verification/verify";
    protected static final String VERIFICATION_SUBJECT = "Your SwiftDrop verification code";
    protected static final String RESET_SUBJECT = "Your SwiftDrop password reset code";
    protected static final String CHANGED_SUBJECT = "Your SwiftDrop password was changed";

    /** Overridden by Testcontainers; never reachable, so a misconfigured test fails rather than using a real database. */
    protected static final String UNREACHABLE_DB_URL = "jdbc:postgresql://tests-must-use-testcontainers.invalid:5432/none";
    protected static final String ADMIN_EMAIL = "admin@test.swiftdrop.lk";
    protected static final String ADMIN_PASSWORD = "AdminPass123";
    protected static final String PASSWORD = "secret123";
    protected static final String COOKIE = "SD_TOKEN";

    /** Phones 071xxxxxxx are only used by tests; the seeder uses 077000000x. */
    private static final AtomicLong PHONE_SEQUENCE = new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000, 5_000_000));

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected CapturingMailSender mail;

    @Autowired
    private AuthRateLimiter rateLimiter;

    /** All requests come from 127.0.0.1, so each test starts with fresh rate-limit counters. */
    @BeforeEach
    void resetRateLimits() {
        rateLimiter.reset();
        mail.failSending(false);
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 12) + "@test.lk";
    }

    /** A fresh mobile number in local format, e.g. 0711234567. */
    protected static String uniquePhone() {
        return "071" + PHONE_SEQUENCE.incrementAndGet();
    }

    /** 0711234567 → +94711234567 */
    protected static String stored(String localPhone) {
        return "+94" + localPhone.substring(1);
    }

    protected static Map<String, Object> customerBody(String email, String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountType", "CUSTOMER");
        body.put("fullName", "Ayesha Fernando");
        body.put("phone", phone);
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("acceptTerms", true);
        return body;
    }

    protected static Map<String, Object> merchantBody(String email, String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountType", "MERCHANT");
        body.put("businessName", "Lanka Crafts");
        body.put("businessRegNo", "PV 00012345");
        body.put("parcelsPerMonth", "FROM_50_TO_200");
        body.put("contactPerson", "Nimal Perera");
        body.put("phone", phone);
        body.put("email", email);
        body.put("pickupDistrict", "COLOMBO");
        body.put("city", "Nugegoda");
        body.put("password", PASSWORD);
        body.put("acceptTerms", true);
        return body;
    }

    protected ResultActions postJson(String path, Object body, Cookie... cookies) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (cookies.length > 0) {
            request.cookie(cookies);
        }
        return mvc.perform(request);
    }

    protected ResultActions register(Map<String, Object> body) throws Exception {
        return postJson("/api/v1/auth/register", body);
    }

    protected ResultActions login(String identifier, String password, boolean rememberMe) throws Exception {
        return postJson("/api/v1/auth/login", Map.of("identifier", identifier, "password", password,
                "rememberMe", rememberMe));
    }

    /** Registers a customer, confirms the email with the emailed code, and returns its SD_TOKEN cookie (ACTIVE). */
    protected Cookie registerCustomer(String email, String phone) throws Exception {
        register(customerBody(email, phone)).andExpect(status().isCreated());
        return tokenCookie(verifyEmail(email).andExpect(status().isOk()).andReturn());
    }

    /** Registers a merchant, confirms the email (status PENDING_APPROVAL) and returns its user id. */
    protected String registerMerchant(String email, String phone) throws Exception {
        MvcResult result = register(merchantBody(email, phone)).andExpect(status().isCreated()).andReturn();
        verifyEmail(email).andExpect(status().isOk());
        return json.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    /** Posts the newest verification code emailed to {@code email}. */
    protected ResultActions verifyEmail(String email) throws Exception {
        return postJson(VERIFY_PATH, Map.of("email", email, "code", mail.awaitCode(email, VERIFICATION_SUBJECT)));
    }

    protected Cookie loginAsAdmin() throws Exception {
        return tokenCookie(login(ADMIN_EMAIL, ADMIN_PASSWORD, false).andExpect(status().isOk()).andReturn());
    }

    protected static Cookie tokenCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(COOKIE);
        assertThat(cookie).as("SD_TOKEN cookie").isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        return new Cookie(COOKIE, cookie.getValue());
    }

    /** The raw Set-Cookie header for SD_TOKEN, or null when there is none. */
    protected static String setCookieHeader(MvcResult result) {
        List<String> headers = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        return headers.stream().filter(h -> h.startsWith(COOKIE + "=")).findFirst().orElse(null);
    }
}
