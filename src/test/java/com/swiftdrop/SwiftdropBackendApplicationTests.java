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

	/** This shared context runs with blank GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET: it starts, and Google is off. */
	@Test
	void googleSignInIsOffWithoutCredentials() throws Exception {
		mvc.perform(get("/oauth2/authorization/google"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(get("/login/oauth2/code/google").param("code", "abc").param("state", "xyz"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void unknownEndpointNeedsLogin() throws Exception {
		mvc.perform(get("/api/v1/nothing-here"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}
}
