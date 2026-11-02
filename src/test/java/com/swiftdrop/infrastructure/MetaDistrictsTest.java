package com.swiftdrop.infrastructure;

import com.swiftdrop.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetaDistrictsTest extends IntegrationTest {

    @Test
    void returnsAll25DistrictsSortedByNameWithoutLogin() throws Exception {
        String body = mvc.perform(get("/api/v1/meta/districts"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode districts = json.readTree(body);
        List<String> names = new ArrayList<>();
        districts.forEach(d -> names.add(d.get("name").asString()));

        assertThat(districts.size()).isEqualTo(25);
        assertThat(names).isSorted().contains("Colombo", "Nuwara Eliya", "Jaffna");
        assertThat(districts.get(0).get("code").asString()).isEqualTo("AMPARA");
        assertThat(districts.get(0).get("name").asString()).isEqualTo("Ampara");
    }
}
