package com.swiftdrop.assistant.internal.tools;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** One thing the assistant can do with SwiftDrop's own APIs; the chat uses it, and MCP clients call it directly. */
public interface AssistantTool {

    /** snake_case, e.g. track_parcel */
    String name();

    /** A short title for MCP clients. */
    String title();

    /** What it does and when to use it, written for AI clients (MCP). */
    String description();

    /** JSON Schema of the arguments ({@code type: object}). */
    Map<String, Object> parameters();

    /** Whether this caller may use it (e.g. own parcels need a logged-in customer or merchant). */
    default boolean availableTo(Caller caller) {
        return true;
    }

    /** Runs it. Bad arguments give {@link ToolResult#failed}, never an exception. */
    ToolResult run(JsonNode arguments, Caller caller);
}
