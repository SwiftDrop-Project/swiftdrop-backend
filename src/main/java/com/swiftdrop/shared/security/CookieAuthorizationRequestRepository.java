package com.swiftdrop.shared.security;

import com.swiftdrop.shared.config.SecurityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.web.util.WebUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the pending Google authorization request (state, nonce, PKCE verifier) in a short-lived cookie
 * instead of an HTTP session, so the backend stays stateless.
 *
 * <p>Cookie value: {@code base64url(json) + "." + base64url(HMAC-SHA256)}. The HMAC key is derived from
 * JWT_SECRET, so the browser can't change the content; the expiry inside the signed content is checked too,
 * not only the cookie's Max-Age. JSON (not Java serialization) so nothing unsafe is ever deserialized.
 */
public class CookieAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String COOKIE_NAME = "SD_OAUTH2_REQUEST";
    static final Duration LIFETIME = Duration.ofMinutes(5);

    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final JsonMapper jsonMapper;
    private final SecurityProperties properties;
    private final SecretKeySpec signingKey;
    private final Clock clock;

    public CookieAuthorizationRequestRepository(JsonMapper jsonMapper, SecurityProperties properties) {
        this(jsonMapper, properties, Clock.systemUTC());
    }

    CookieAuthorizationRequestRepository(JsonMapper jsonMapper, SecurityProperties properties, Clock clock) {
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.clock = clock;
        // A separate key, so this cookie can never be confused with an SD_TOKEN signature.
        this.signingKey = new SecretKeySpec(
                hmac(properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "swiftdrop-oauth2-authorization-request"),
                HMAC);
    }

    /** What is stored. Everything the login filter needs after Google redirects back. */
    record StoredRequest(String authorizationUri, String clientId, String redirectUri, Set<String> scopes, String state,
                         Map<String, Object> additionalParameters, Map<String, Object> attributes, long expiresAt) {
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        Cookie cookie = WebUtils.getCookie(request, COOKIE_NAME);
        if (state == null || cookie == null) {
            return null;
        }
        StoredRequest stored = verify(cookie.getValue());
        if (stored == null || !state.equals(stored.state())) {
            return null;
        }
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(stored.authorizationUri())
                .clientId(stored.clientId())
                .redirectUri(stored.redirectUri())
                .scopes(stored.scopes())
                .state(stored.state())
                .additionalParameters(stored.additionalParameters())
                .attributes(stored.attributes())
                .build();
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
                                         HttpServletResponse response) {
        if (authorizationRequest == null) {
            clear(response);
            return;
        }
        StoredRequest stored = new StoredRequest(authorizationRequest.getAuthorizationUri(),
                authorizationRequest.getClientId(), authorizationRequest.getRedirectUri(),
                authorizationRequest.getScopes(), authorizationRequest.getState(),
                authorizationRequest.getAdditionalParameters(), authorizationRequest.getAttributes(),
                clock.instant().plus(LIFETIME).getEpochSecond());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(sign(stored), LIFETIME).toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest loaded = loadAuthorizationRequest(request);
        if (WebUtils.getCookie(request, COOKIE_NAME) != null) {
            clear(response);
        }
        return loaded;
    }

    private void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAge)
                .build();
    }

    private String sign(StoredRequest stored) {
        String payload = ENCODER.encodeToString(jsonMapper.writeValueAsBytes(stored));
        return payload + "." + ENCODER.encodeToString(hmac(signingKey, payload));
    }

    /** The stored request, or null if the value was changed, is malformed or has expired. */
    private StoredRequest verify(String value) {
        int dot = value.indexOf('.');
        if (dot <= 0 || dot != value.lastIndexOf('.')) {
            return null;
        }
        String payload = value.substring(0, dot);
        try {
            byte[] signature = DECODER.decode(value.substring(dot + 1));
            if (!MessageDigest.isEqual(signature, hmac(signingKey, payload))) {
                return null;
            }
            StoredRequest stored = jsonMapper.readValue(DECODER.decode(payload), StoredRequest.class);
            return clock.instant().getEpochSecond() < stored.expiresAt() ? stored : null;
        } catch (IllegalArgumentException | JacksonException ex) {
            return null;
        }
    }

    private static byte[] hmac(SecretKeySpec key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        return hmac(new SecretKeySpec(key, HMAC), data);
    }
}
