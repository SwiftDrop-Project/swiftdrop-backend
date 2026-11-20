package com.swiftdrop.assistant.internal.application;

import com.swiftdrop.assistant.internal.tools.AssistantTool;
import com.swiftdrop.assistant.internal.tools.AssistantTools;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.assistant.internal.tools.ToolResult;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A minimal MCP server (Model Context Protocol, Streamable HTTP transport without event streams): JSON-RPC 2.0
 * {@code initialize}, {@code ping}, {@code tools/list} and {@code tools/call} over the assistant's public tools. No
 * sessions: every request stands alone.
 */
@Service
public class McpService {

    /** Newest first; a client asking for another version gets the newest. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    private final AssistantTools tools;
    private final AssistantRateLimiter rateLimiter;
    private final JsonMapper json;

    public McpService(AssistantTools tools, AssistantRateLimiter rateLimiter, JsonMapper json) {
        this.tools = tools;
        this.rateLimiter = rateLimiter;
        this.json = json;
    }

    /**
     * Handles one JSON-RPC message.
     *
     * @return the response, or empty for a notification (no {@code id}), which gets HTTP 202
     */
    public Optional<ObjectNode> handle(JsonNode message, Caller caller) {
        if (message == null || !message.isObject()) {
            return Optional.of(error(null, INVALID_REQUEST, "Send one JSON-RPC 2.0 request object."));
        }
        JsonNode id = message.get("id");
        String method = message.path("method").asString("");
        if (id == null || id.isNull()) {
            // Notifications (e.g. notifications/initialized) and responses need no answer.
            return Optional.empty();
        }
        if (!"2.0".equals(message.path("jsonrpc").asString("")) || method.isEmpty()) {
            return Optional.of(error(id, INVALID_REQUEST, "Not a JSON-RPC 2.0 request."));
        }
        JsonNode params = message.path("params");
        return Optional.of(switch (method) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, json.createObjectNode());
            case "tools/list" -> result(id, listTools(caller));
            case "tools/call" -> callTool(id, params, caller);
            default -> error(id, METHOD_NOT_FOUND, "Method not found: " + method);
        });
    }

    private ObjectNode initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asString("");
        ObjectNode result = json.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.getFirst());
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode server = result.putObject("serverInfo");
        server.put("name", "swiftdrop");
        server.put("title", "SwiftDrop delivery tools");
        server.put("version", "1.0.0");
        result.put("instructions", "Tools for SwiftDrop, a parcel delivery service in Sri Lanka: track a parcel by "
                + "tracking number, estimate a delivery fee in LKR between districts, and list the 25 districts. "
                + "Public data only: no names, phones or addresses.");
        return result;
    }

    private ObjectNode listTools(Caller caller) {
        ObjectNode result = json.createObjectNode();
        ArrayNode list = result.putArray("tools");
        for (AssistantTool tool : tools.availableTo(caller)) {
            ObjectNode entry = list.addObject();
            entry.put("name", tool.name());
            entry.put("title", tool.title());
            entry.put("description", tool.description());
            entry.set("inputSchema", json.valueToTree(tool.parameters()));
            entry.putObject("annotations").put("readOnlyHint", true);
        }
        return result;
    }

    private ObjectNode callTool(JsonNode id, JsonNode params, Caller caller) {
        String name = params.path("name").asString("");
        if (tools.find(name, caller).isEmpty()) {
            return error(id, INVALID_PARAMS, "Unknown tool: " + name);
        }
        ToolResult outcome;
        try {
            rateLimiter.check(caller.clientKey());
            outcome = tools.run(name, params.path("arguments"), caller);
        } catch (ApiException e) {
            if (e.code() != ErrorCode.TOO_MANY_REQUESTS) {
                throw e;
            }
            outcome = ToolResult.failed(e.getMessage());
        }
        ObjectNode result = json.createObjectNode();
        JsonNode data = json.valueToTree(outcome.data());
        result.putArray("content").addObject()
                .put("type", "text")
                .put("text", json.writeValueAsString(data));
        result.set("structuredContent", data);
        result.put("isError", outcome.error());
        return result(id, result);
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? json.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}
