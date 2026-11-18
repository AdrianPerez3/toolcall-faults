package toolcallfaults.runner.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.*;
import toolcallfaults.runner.fixture.FixtureLoader;

public final class RequestBodies {
    private RequestBodies() {}

    static JsonNode parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode request = FixtureLoader.JSON.readTree(body);
            return request != null && request.isObject() ? request : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean parseable(String body) {
        return parse(body) != null;
    }

    public static List<ToolResult> toolResults(String wire, String body) {
        JsonNode request = parse(body);
        return request == null ? List.of() : toolResults(wire, request);
    }

    static List<ToolResult> toolResults(String wire, JsonNode request) {
        List<ToolResult> results = new ArrayList<>();
        for (JsonNode message : request.path("messages")) {
            String role = message.path("role").asText();
            if (wire.equals("openai-chat") && role.equals("tool")
                    && message.path("tool_call_id").isTextual()) {
                results.add(new ToolResult(message.get("tool_call_id").textValue(), text(message.path("content"))));
            }
            if (wire.equals("anthropic") && role.equals("user")) {
                for (JsonNode block : message.path("content")) {
                    if (block.path("type").asText().equals("tool_result") && block.path("tool_use_id").isTextual()) {
                        results.add(new ToolResult(block.get("tool_use_id").textValue(), text(block.path("content"))));
                    }
                }
            }
        }
        return results;
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) return content.textValue();
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if (block.path("type").asText().equals("text")) text.append(block.path("text").asText());
        }
        return text.toString();
    }
}
