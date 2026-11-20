package com.swiftdrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.assistant.internal.application.AssistantRateLimiter;
import com.swiftdrop.tracking.internal.application.TrackingRateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** The MCP server at /api/v1/mcp: JSON-RPC 2.0 over HTTP, public tools only. */
class McpServerTest extends IntegrationTest {

    private static final String MCP = "/api/v1/mcp";

    @Autowired
    AssistantRateLimiter assistantLimiter;

    @Autowired
    TrackingRateLimiter trackingLimiter;

    @BeforeEach
    void resetLimits() {
        assistantLimiter.reset();
        trackingLimiter.reset();
    }

    @Test
    void initializesWithoutALogin() throws Exception {
        rpc(1, "initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("swiftdrop"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists());

        postJson(MCP, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                .andExpect(status().isAccepted());
    }

    @Test
    void anUnknownProtocolVersionGetsTheNewest() throws Exception {
        rpc(1, "initialize", Map.of("protocolVersion", "1999-01-01"))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"));
    }

    @Test
    void listsOnlyThePublicTools() throws Exception {
        JsonNode tools = read(rpc(2, "tools/list", Map.of())).path("result").path("tools");

        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asString("")));
        assertThat(names).containsExactlyInAnyOrder("estimate_price", "list_districts", "track_parcel");
        tools.forEach(tool -> {
            assertThat(tool.path("inputSchema").path("type").asString("")).isEqualTo("object");
            assertThat(tool.path("description").asString("")).isNotBlank();
        });
    }

    @Test
    void callsATool() throws Exception {
        rpc(3, "tools/call", Map.of("name", "estimate_price", "arguments", Map.of(
                "pickupDistrict", "COLOMBO", "dropOffDistrict", "KANDY", "weightKg", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.structuredContent.status").value("PROVISIONAL"))
                .andExpect(jsonPath("$.result.structuredContent.currency").value("LKR"));

        rpc(4, "tools/call", Map.of("name", "track_parcel", "arguments", Map.of("trackingNumber", "SD2611999999")))
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.found").value(false));
    }

    @Test
    void badArgumentsAreAToolError() throws Exception {
        rpc(5, "tools/call", Map.of("name", "estimate_price", "arguments", Map.of(
                "pickupDistrict", "ATLANTIS", "dropOffDistrict", "KANDY", "weightKg", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.error").exists());
    }

    @Test
    void unknownToolsAndMethodsAreJsonRpcErrors() throws Exception {
        rpc(6, "tools/call", Map.of("name", "my_parcels", "arguments", Map.of()))
                .andExpect(jsonPath("$.error.code").value(-32602));
        rpc(7, "resources/list", Map.of())
                .andExpect(jsonPath("$.error.code").value(-32601));
        postJson(MCP, Map.of("id", 8, "method", "tools/list"))
                .andExpect(jsonPath("$.error.code").value(-32600));
    }

    @Test
    void getIsNotAnEventStream() throws Exception {
        int status = mvc.perform(get(MCP)).andReturn().getResponse().getStatus();
        // 405, never 401: a 401 would make MCP clients start an OAuth login.
        assertThat(status).isEqualTo(405);
    }

    @Test
    void toolCallsAreLimitedPerClient() throws Exception {
        for (int i = 0; i < 20; i++) {
            rpc(i, "tools/call", Map.of("name", "list_districts", "arguments", Map.of()))
                    .andExpect(jsonPath("$.result.isError").value(false));
        }
        rpc(99, "tools/call", Map.of("name", "list_districts", "arguments", Map.of()))
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.error")
                        .value(org.hamcrest.Matchers.containsString("Too many")));
    }

    private ResultActions rpc(int id, String method, Map<String, Object> params) throws Exception {
        return postJson(MCP, Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
