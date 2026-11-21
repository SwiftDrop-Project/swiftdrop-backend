package com.swiftdrop.assistant.internal.controller;

import com.swiftdrop.assistant.internal.application.McpService;
import com.swiftdrop.assistant.internal.tools.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * SwiftDrop's tools as an MCP server, for MCP clients such as MCP Inspector or Claude Desktop: Streamable HTTP
 * transport, plain JSON responses (no event stream, so GET is 405).
 */
@RestController
@Tag(name = "Assistant")
class McpController {

    private final McpService mcp;

    McpController(McpService mcp) {
        this.mcp = mcp;
    }

    @Operation(summary = """
            MCP server (JSON-RPC 2.0): initialize, ping, tools/list, tools/call. Public tools only: track_parcel, \
            estimate_price, list_districts. Notifications get 202.""")
    @PreAuthorize("permitAll()")
    @PostMapping(path = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> handle(@RequestBody JsonNode message, HttpServletRequest http) {
        return mcp.handle(message, Caller.anonymous(http.getRemoteAddr()))
                .<ResponseEntity<JsonNode>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.accepted().build());
    }
}
