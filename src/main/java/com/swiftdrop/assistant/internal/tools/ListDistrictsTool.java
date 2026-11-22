package com.swiftdrop.assistant.internal.tools;

import com.swiftdrop.shared.reference.District;
import java.util.Arrays;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** The 25 districts SwiftDrop delivers to, each with a branch. */
@Component
class ListDistrictsTool implements AssistantTool {

    @Override
    public String name() {
        return "list_districts";
    }

    @Override
    public String title() {
        return "List delivery districts";
    }

    @Override
    public String description() {
        return "List the districts of Sri Lanka SwiftDrop picks up from and delivers to (all 25, each with a "
                + "SwiftDrop branch), with their codes.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult run(JsonNode arguments, Caller caller) {
        return ToolResult.of(Map.of(
                "count", District.values().length,
                "districts", Arrays.stream(District.values())
                        .map(d -> Map.of("code", d.name(), "name", d.displayName()))
                        .toList()), null);
    }
}
