package com.swiftdrop.infrastructure;

import com.swiftdrop.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorrelationIdTest extends IntegrationTest {

    private static final String HEADER = "X-Correlation-Id";

    @Test
    void newIdWhenTheCallerSendsNone() throws Exception {
        String id = mvc.perform(get("/api/v1/meta/districts"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HEADER);

        assertThat(id).matches("[0-9a-f-]{36}");
    }

    @Test
    void callersIdIsKept() throws Exception {
        mvc.perform(get("/api/v1/meta/districts").header(HEADER, "frontend-1234"))
                .andExpect(header().string(HEADER, "frontend-1234"));
    }

    @Test
    void unsafeIdIsReplaced() throws Exception {
        String id = mvc.perform(get("/api/v1/meta/districts").header(HEADER, "bad id; drop table"))
                .andReturn().getResponse().getHeader(HEADER);

        assertThat(id).matches("[0-9a-f-]{36}");
    }

    @Test
    void errorResponsesHaveItToo() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HEADER));
    }
}
