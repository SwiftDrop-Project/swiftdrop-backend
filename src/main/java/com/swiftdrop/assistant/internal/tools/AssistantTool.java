package com.swiftdrop.assistant.internal.tools;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** One thing the assistant can do, described once for both the chat model and MCP clients. */
public interface AssistantTool {

    /** snake_case, e.g. track_parcel */
    String name();

    /** A short title for MCP clients. */
    String title();

    /** What it does and when to use it, written for the model. */
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
