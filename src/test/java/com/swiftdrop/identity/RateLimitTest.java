package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitTest extends IntegrationTest {

    @Test
    void eleventhLoginFromOneAddressWithinAMinuteGets429() throws Exception {
        for (int i = 0; i < 10; i++) {
            loginFrom("203.0.113.10").andExpect(status().isUnauthorized());
        }

        String retryAfter = loginFrom("203.0.113.10")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.message").value("Too many attempts. Please wait a moment and try again."))
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, 60);

        // Other addresses and other endpoints have their own counters.
        loginFrom("203.0.113.11").andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/password/forgot").with(from("203.0.113.10"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", uniqueEmail()))))
                .andExpect(status().isAccepted());
    }

    @Test
    void theFrontendCanReadRetryAfter() throws Exception {
        for (int i = 0; i < 10; i++) {
            loginFrom("203.0.113.30").andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/auth/login").with(from("203.0.113.30"))
                        .header("Origin", "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", "nobody@test.lk", "password", "wrong123"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void forgotIsLimitedToFivePerMinute() throws Exception {
        for (int i = 0; i < 5; i++) {
            forgotFrom("203.0.113.20").andExpect(status().isAccepted());
        }
        forgotFrom("203.0.113.20").andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.ResultActions loginFrom(String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("identifier", "nobody@test.lk", "password", "wrong123"))));
    }

    private org.springframework.test.web.servlet.ResultActions forgotFrom(String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/forgot").with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("identifier", uniqueEmail()))));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
