package com.swiftdrop.assistant.internal.tools;

import java.util.Map;

/**
 * What a tool returns: the data (from SwiftDrop's APIs), and optionally a card for the chat UI.
 *
 * @param error true when the tool couldn't do it; {@code data.error} says why, in words a person can act on
 */
public record ToolResult(Map<String, Object> data, AssistantCard card, boolean error) {

    public static ToolResult of(Map<String, Object> data, AssistantCard card) {
        return new ToolResult(data, card, false);
    }

    public static ToolResult failed(String message) {
        return new ToolResult(Map.of("error", message), null, true);
    }
}
