package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.identity.internal.application.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tokens are checked against the database (status and token version) on every request. */
class SessionValidityTest extends IntegrationTest {

    @Autowired
    UserService userService;

    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void disablingAnAccountEndsItsSessionsAndClearsTheCookie() throws Exception {
        String email = uniqueEmail();
        Cookie cookie = registerCustomer(email, uniquePhone());
        UUID id = userService.findLoginAccountByEmail(email).orElseThrow().id();

        userService.changeStatus(id, UserStatus.DISABLED);

        MvcResult result = mvc.perform(get("/api/v1/auth/me").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();
        assertThat(setCookieHeader(result)).contains("Max-Age=0");
    }

    @Test
    void tokenWithoutOrWithAnOldVersionIsRejected() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());
        UUID id = userService.findLoginAccountByEmail(email).orElseThrow().id();

        mvc.perform(get("/api/v1/auth/me").cookie(token(id, null))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(token(id, 7))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(token(id, 0))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(token(UUID.randomUUID(), 0))).andExpect(status().isUnauthorized());
    }

    /** A correctly signed customer token for {@code userId}; {@code version} null leaves out the "ver" claim. */
    private Cookie token(UUID userId, Integer version) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim("role", "CUSTOMER")
                .claim("status", "ACTIVE")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600));
        if (version != null) {
            claims.claim("ver", version);
        }
        String value = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims.build())).getTokenValue();
        return new Cookie(COOKIE, value);
    }
}
