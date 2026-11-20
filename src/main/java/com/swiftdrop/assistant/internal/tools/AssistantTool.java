package com.swiftdrop.assistant.internal.tools;

import tools.jackson.databind.JsonNode;

/** One thing the assistant can do with SwiftDrop's own APIs (tracking, pricing, districts). */
public interface AssistantTool {

    /** snake_case, e.g. track_parcel */
    String name();

    /** Whether this caller may use it (e.g. own parcels need a logged-in customer or merchant). */
    default boolean availableTo(Caller caller) {
        return true;
    }

    /** Runs it. Bad arguments give {@link ToolResult#failed}, never an exception. */
    ToolResult run(JsonNode arguments, Caller caller);
}
