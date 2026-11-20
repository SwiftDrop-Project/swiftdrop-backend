package com.swiftdrop.assistant.internal.tools;

import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Every assistant tool, for the chat and for MCP. */
@Component
public class AssistantTools {

    private static final Logger log = LoggerFactory.getLogger(AssistantTools.class);

    private final List<AssistantTool> tools;

    public AssistantTools(List<AssistantTool> tools) {
        this.tools = tools.stream().sorted(Comparator.comparing(AssistantTool::name)).toList();
    }

    /** The tools this caller may use. */
    public List<AssistantTool> availableTo(Caller caller) {
        return tools.stream().filter(tool -> tool.availableTo(caller)).toList();
    }

    public Optional<AssistantTool> find(String name, Caller caller) {
        return availableTo(caller).stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    /**
     * Runs a tool. An unknown tool or an unexpected failure becomes a failed result the answer can explain; only a
     * rate limit is thrown (429 for the whole request).
     */
    public ToolResult run(String name, JsonNode arguments, Caller caller) {
        Optional<AssistantTool> tool = find(name, caller);
        if (tool.isEmpty()) {
            return ToolResult.failed("There is no tool called " + name + ".");
        }
        JsonNode args = arguments == null || arguments.isNull() || arguments.isMissingNode()
                ? JsonNodeFactory.instance.objectNode() : arguments;
        try {
            return tool.get().run(args, caller);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.TOO_MANY_REQUESTS) {
                throw e;
            }
            return ToolResult.failed(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Assistant tool {} failed", name, e);
            return ToolResult.failed("That didn't work. Try again in a moment.");
        }
    }
}
