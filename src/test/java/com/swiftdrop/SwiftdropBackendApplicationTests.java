package com.swiftdrop;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SwiftdropBackendApplicationTests extends IntegrationTest {

	@Test
	void contextLoads() {
	}

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void otherActuatorEndpointsAreNotExposed() throws Exception {
		mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
	}

	@Test
	void swaggerIsPublic() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists());
		mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
	}

	@Test
	void unknownEndpointNeedsLogin() throws Exception {
		mvc.perform(get("/api/v1/nothing-here"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}
}
