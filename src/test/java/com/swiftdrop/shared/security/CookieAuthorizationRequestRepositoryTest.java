package com.swiftdrop.shared.security;

import com.swiftdrop.shared.config.SecurityProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CookieAuthorizationRequestRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final SecurityProperties PROPERTIES = new SecurityProperties("http://localhost:3000", false,
            new SecurityProperties.Jwt("test-only-jwt-secret-0123456789-abcdefghijklmnopqrstuvwxyz", 60, 7), null);

    private final OAuth2AuthorizationRequest original = OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .clientId("client-id")
            .redirectUri("http://localhost:8080/login/oauth2/code/google")
            .scope("openid", "profile", "email")
            .state("state-123")
            .additionalParameters(Map.of("nonce", "hashed-nonce"))
            .attributes(Map.of("registration_id", "google", "nonce", "raw-nonce", "code_verifier", "verifier"))
            .build();

    @Test
    void savedRequestComesBackUnchanged() {
        Cookie cookie = save(NOW);

        OAuth2AuthorizationRequest loaded = repository(NOW.plusSeconds(60)).loadAuthorizationRequest(callback("state-123", cookie));

        assertThat(loaded).isNotNull();
        assertThat(loaded.getAuthorizationUri()).isEqualTo(original.getAuthorizationUri());
        assertThat(loaded.getClientId()).isEqualTo("client-id");
        assertThat(loaded.getRedirectUri()).isEqualTo(original.getRedirectUri());
        assertThat(loaded.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(loaded.getState()).isEqualTo("state-123");
        assertThat(loaded.getAdditionalParameters()).isEqualTo(original.getAdditionalParameters());
        assertThat(loaded.getAttributes()).isEqualTo(original.getAttributes());
    }

    @Test
    void expiresAfterFiveMinutesEvenIfTheBrowserKeepsTheCookie() {
        Cookie cookie = save(NOW);

        assertThat(repository(NOW.plus(Duration.ofMinutes(5)).plusSeconds(1))
                .loadAuthorizationRequest(callback("state-123", cookie))).isNull();
    }

    @Test
    void changedCookieOrOtherStateIsIgnored() {
        Cookie cookie = save(NOW);
        String value = cookie.getValue();
        String payload = value.substring(0, value.indexOf('.'));
        String otherPayload = payload.substring(0, payload.length() - 2) + (payload.endsWith("A") ? "BB" : "AA");
        CookieAuthorizationRequestRepository repository = repository(NOW);

        assertThat(repository.loadAuthorizationRequest(callback("other-state", cookie))).isNull();
        assertThat(repository.loadAuthorizationRequest(callback("state-123",
                new Cookie(cookie.getName(), otherPayload + value.substring(value.indexOf('.')))))).isNull();
        assertThat(repository.loadAuthorizationRequest(callback("state-123",
                new Cookie(cookie.getName(), "not-a-valid-value")))).isNull();
    }

    @Test
    void removeReturnsTheRequestAndClearsTheCookie() {
        Cookie cookie = save(NOW);
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthorizationRequest removed = repository(NOW).removeAuthorizationRequest(callback("state-123", cookie),
                response);

        assertThat(removed).isNotNull();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith(CookieAuthorizationRequestRepository.COOKIE_NAME + "=;")
                .contains("Max-Age=0");
    }

    private Cookie save(Instant at) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository(at).saveAuthorizationRequest(original, new MockHttpServletRequest(), response);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("HttpOnly", "SameSite=Lax", "Max-Age=300");
        return response.getCookie(CookieAuthorizationRequestRepository.COOKIE_NAME);
    }

    private static CookieAuthorizationRequestRepository repository(Instant now) {
        return new CookieAuthorizationRequestRepository(JsonMapper.builder().build(), PROPERTIES,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static MockHttpServletRequest callback(String state, Cookie cookie) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        request.setParameter("state", state);
        request.setCookies(cookie);
        return request;
    }
}
